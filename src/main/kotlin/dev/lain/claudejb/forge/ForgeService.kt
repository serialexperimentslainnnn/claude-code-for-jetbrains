package dev.lain.claudejb.forge

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger

/**
 * The two questions this package answers, and the only thing outside it should call.
 *
 * **What the UI must do with the answers.** `Known` is drawable — including `Known(emptyList())` and
 * `Known(null)`, which mean "asked, and there is nothing open / no run yet". [ForgeAnswer.Silent] is not: the
 * card is omitted entirely, with no placeholder and no "configure me" prompt. The user did not ask for a
 * forge integration and must not be nagged about one; the reason exists for `idea.log`.
 *
 * **Threading: every call blocks and must be made from a pooled thread.** Both spend a network round trip, so
 * a call on the EDT would freeze the IDE for as long as the far end takes. Rather than hop off the thread —
 * which would deliver the answer after the caller had already returned — this refuses and says so in the log,
 * exactly as `GitHistoryService.recentCommits` refuses to run `git log` on the EDT.
 */
object ForgeService {

    private val LOG = logger<ForgeService>()

    /** Open pull/merge requests whose source branch is [branch]. Blocking — see the class KDoc. */
    fun openPullRequests(repo: ForgeRepo, branch: String): ForgeAnswer<List<ForgePullRequest>> {
        val api = apiFor(repo.provider)
        return when (val body = fetch(repo, branch, api::pullRequests)) {
            is ForgeAnswer.Silent -> body
            is ForgeAnswer.Known -> api.parsePullRequests(body.value)
        }
    }

    /**
     * The LAST CI run of [branch] — one indicator, not a job graph. Blocking — see the class KDoc.
     *
     * `Known(null)` is a real answer: the branch exists and no workflow or pipeline has ever run on it.
     */
    fun lastRun(repo: ForgeRepo, branch: String): ForgeAnswer<ForgeRun?> {
        val api = apiFor(repo.provider)
        return when (val body = fetch(repo, branch, api::latestRun)) {
            is ForgeAnswer.Silent -> body
            is ForgeAnswer.Known -> api.parseLatestRun(body.value)
        }
    }

    /**
     * The four gates every call passes, in this order and for this reason: each one is strictly cheaper than
     * the next, and the last of them is the only one that costs a network round trip. In particular the token
     * lookup precedes the request being built at all, so a project on a host nobody has a token for never
     * reaches [ForgeHttp] and never opens a socket.
     */
    private fun fetch(
        repo: ForgeRepo,
        branch: String,
        build: (ForgeRepo, String, String) -> ForgeRequest,
    ): ForgeAnswer<String> {
        if (ApplicationManager.getApplication()?.isDispatchThread == true) {
            LOG.warn("A forge query was made on the EDT; refusing it. Move the call to a pooled thread.")
            return ForgeAnswer.Silent(ForgeSilence.ON_EDT)
        }
        if (branch.isBlank()) return ForgeAnswer.Silent(ForgeSilence.NO_BRANCH)
        if (!isUsableHost(repo.host)) return ForgeAnswer.Silent(ForgeSilence.UNSUPPORTED_HOST)
        val token = ForgeTokens.get(repo.host) ?: return ForgeAnswer.Silent(ForgeSilence.NO_TOKEN)
        return ForgeHttp.fetch(build(repo, branch, token))
    }
}
