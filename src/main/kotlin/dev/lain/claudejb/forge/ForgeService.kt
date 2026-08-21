package dev.lain.claudejb.forge

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger

object ForgeService {

    private val LOG = logger<ForgeService>()

    fun openPullRequests(repo: ForgeRepo): ForgeAnswer<List<ForgePullRequest>> {
        val api = apiFor(repo.provider)
        return when (val body = fetch(repo, "", api::pullRequests, requireBranch = false)) {
            is ForgeAnswer.Silent -> body
            is ForgeAnswer.Known -> api.parsePullRequests(body.value)
        }
    }

    fun runs(repo: ForgeRepo, branch: String): ForgeAnswer<List<ForgeRun>> {
        val api = apiFor(repo.provider)
        return when (val body = fetch(repo, branch, api::runs)) {
            is ForgeAnswer.Silent -> body
            is ForgeAnswer.Known -> api.parseRuns(body.value)
        }
    }

    fun access(repo: ForgeRepo): ForgeAnswer<ForgeAccess> {
        val api = apiFor(repo.provider)
        val ask = { r: ForgeRepo, _: String, token: String -> api.access(r, token) }
        val level = when (val body = fetch(repo, "", ask, requireBranch = false)) {
            is ForgeAnswer.Silent -> return body
            is ForgeAnswer.Known -> api.parseAccess(body.value)
        }
        return when (level) {
            is ForgeAnswer.Silent -> level
            is ForgeAnswer.Known -> ForgeAnswer.Known(ForgeAccess(level.value, viewer(repo, api)))
        }
    }

    private fun viewer(repo: ForgeRepo, api: ForgeApi): String? {
        viewers[repo.host]?.let { return it.orNull() }
        val ask = { r: ForgeRepo, _: String, token: String -> api.viewer(r, token) }
        val name = when (val body = fetch(repo, "", ask, requireBranch = false)) {
            is ForgeAnswer.Silent -> null
            is ForgeAnswer.Known -> (api.parseViewer(body.value) as? ForgeAnswer.Known)?.value
        }
        viewers[repo.host] = Viewer(name)
        return name
    }

    private class Viewer(val name: String?) {
        fun orNull(): String? = name
    }

    private val viewers = java.util.concurrent.ConcurrentHashMap<String, Viewer>()

    fun approve(repo: ForgeRepo, number: Long): ForgeOutcome =
        act(repo) { r, token -> apiFor(r.provider).approve(r, number, token) }

    fun unapprove(repo: ForgeRepo, number: Long): ForgeOutcome {
        val api = apiFor(repo.provider)
        return actOrNull(repo) { r, token -> api.unapprove(r, number, token) }
            ?: ForgeOutcome.Refused(ForgeRefusal.UNSUPPORTED)
    }

    fun merge(repo: ForgeRepo, number: Long): ForgeOutcome =
        act(repo) { r, token -> apiFor(r.provider).merge(r, number, token) }

    fun comment(repo: ForgeRepo, number: Long, text: String): ForgeOutcome =
        act(repo) { r, token -> apiFor(r.provider).comment(r, number, text, token) }

    fun openPullRequest(repo: ForgeRepo, source: String, target: String, title: String): ForgeOutcome =
        act(repo) { r, token -> apiFor(r.provider).openPullRequest(r, source, target, title, token) }

    fun canUnapprove(repo: ForgeRepo): Boolean = apiFor(repo.provider).unapprove(repo, 1, "probe") != null

    fun comments(repo: ForgeRepo, number: Long): List<String> {
        val api = apiFor(repo.provider)
        val body = fetch(repo, "", { r, _, token -> api.comments(r, number, token) }, requireBranch = false)
        return when (body) {
            is ForgeAnswer.Silent -> emptyList()
            is ForgeAnswer.Known -> (api.parseComments(body.value) as? ForgeAnswer.Known)?.value.orEmpty()
        }
    }

    fun failedJobLog(repo: ForgeRepo, runId: Long): Pair<String?, Redacted?> {
        val api = apiFor(repo.provider)
        val listing = fetch(repo, "", { r, _, token -> api.jobs(r, runId, token) }, requireBranch = false)
        val jobs = when (listing) {
            is ForgeAnswer.Silent -> return null to null
            is ForgeAnswer.Known -> (api.parseJobs(listing.value) as? ForgeAnswer.Known)?.value.orEmpty()
        }
        val job = jobs.firstOrNull { it.failed } ?: jobs.lastOrNull() ?: return null to null
        val trace = fetch(repo, "", { r, _, token -> api.jobLog(r, job.id, token) }, requireBranch = false)
        return when (trace) {
            is ForgeAnswer.Silent -> job.name to null
            is ForgeAnswer.Known -> job.name to SecretRedactor.scrub(tail(trace.value))
        }
    }

    private fun tail(log: String): String {
        val lines = log.lines()
        return if (lines.size <= MAX_LOG_LINES) log else lines.takeLast(MAX_LOG_LINES).joinToString("\n")
    }

    private const val MAX_LOG_LINES = 400

    fun retryRun(repo: ForgeRepo, runId: Long): ForgeOutcome =
        act(repo) { r, token -> apiFor(r.provider).retryRun(r, runId, token) }

    fun cancelRun(repo: ForgeRepo, runId: Long): ForgeOutcome =
        act(repo) { r, token -> apiFor(r.provider).cancelRun(r, runId, token) }

    private fun act(repo: ForgeRepo, build: (ForgeRepo, String) -> ForgeRequest): ForgeOutcome =
        actOrNull(repo) { r, token -> build(r, token) } ?: ForgeOutcome.Refused(ForgeRefusal.UNSUPPORTED)

    private fun actOrNull(repo: ForgeRepo, build: (ForgeRepo, String) -> ForgeRequest?): ForgeOutcome? {
        if (ApplicationManager.getApplication()?.isDispatchThread == true) {
            LOG.warn("A forge action was asked for on the EDT; refusing it. Move the call to a pooled thread.")
            return ForgeOutcome.Refused(ForgeRefusal.ON_EDT)
        }
        if (!isUsableHost(repo.host)) return ForgeOutcome.Refused(ForgeRefusal.UNREACHABLE)
        val token = ForgeTokens.get(repo.host) ?: return ForgeOutcome.Refused(ForgeRefusal.NO_TOKEN)
        return ForgeHttp.act(build(repo, token) ?: return null)
    }

    private fun fetch(
        repo: ForgeRepo,
        branch: String,
        build: (ForgeRepo, String, String) -> ForgeRequest,
        requireBranch: Boolean = true,
    ): ForgeAnswer<String> {
        if (ApplicationManager.getApplication()?.isDispatchThread == true) {
            LOG.warn("A forge query was made on the EDT; refusing it. Move the call to a pooled thread.")
            return ForgeAnswer.Silent(ForgeSilence.ON_EDT)
        }
        if (requireBranch && branch.isBlank()) return ForgeAnswer.Silent(ForgeSilence.NO_BRANCH)
        if (!isUsableHost(repo.host)) return ForgeAnswer.Silent(ForgeSilence.UNSUPPORTED_HOST)
        val token = ForgeTokens.get(repo.host) ?: return ForgeAnswer.Silent(ForgeSilence.NO_TOKEN)
        return ForgeHttp.fetch(build(repo, branch, token))
    }
}
