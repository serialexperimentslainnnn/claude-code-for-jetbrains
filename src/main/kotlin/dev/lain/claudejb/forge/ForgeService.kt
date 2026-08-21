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
