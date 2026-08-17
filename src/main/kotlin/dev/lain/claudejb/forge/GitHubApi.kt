package dev.lain.claudejb.forge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.net.URI

/**
 * GitHub (and GitHub Enterprise Server) over the REST API.
 *
 * Two endpoints, both verified against the current official reference:
 *  - `GET /repos/{owner}/{repo}/pulls?state=open&head={owner}:{branch}` — *List pull requests*. The `head`
 *    filter's documented form is `user:ref-name`, not a bare branch, and a bare branch silently matches
 *    nothing at all rather than erroring.
 *  - `GET /repos/{owner}/{repo}/actions/runs?branch={branch}&per_page=1` — *List workflow runs for a
 *    repository*. Newest first, so one page of one is the last run. The envelope is
 *    `{"total_count":…, "workflow_runs":[…]}` and NOT a bare array.
 *
 * **The `head` filter cannot see a fork.** It matches `{owner}:{branch}` against the HEAD repository, so a
 * pull request opened from a contributor's fork of the same branch name is not returned. That is accepted:
 * widening it means listing every open pull request and filtering client-side, which turns one small request
 * into pagination over a busy repository for a card that is showing the current user their own branch.
 */
internal object GitHubApi : ForgeApi {

    /**
     * The REST version we coded these field names against, sent explicitly.
     *
     * Omitting the header defaults to this same value *today* — which is precisely why it is sent: the
     * default moves when GitHub promotes a newer version, and a silent default is a card that breaks on
     * GitHub's schedule rather than on ours. An unsupported version answers `410 Gone`, i.e. a `Silent` card
     * and a line in the log, instead of a payload whose shape has quietly changed underneath the models below.
     */
    private const val API_VERSION = "2022-11-28"

    private const val DOT_COM = "github.com"

    /** Enough to fill the card; a branch with more than this many open pull requests is not a real case. */
    private const val PULL_REQUEST_LIMIT = 20

    /** The run `status` values that mean "not finished". Everything else is judged by `conclusion`. */
    private val IN_FLIGHT = setOf("queued", "in_progress", "waiting", "requested", "pending")

    /** Conclusions GitHub itself does not treat as a failure. */
    private val NOT_FAILING = setOf("success", "neutral")

    private val FAILING = setOf("failure", "timed_out", "action_required", "startup_failure")

    private val ABANDONED = setOf("cancelled", "skipped", "stale")

    override fun pullRequests(repo: ForgeRepo, branch: String, token: String): ForgeRequest =
        ForgeRequest(
            URI.create(
                "${base(repo.host)}/repos/${pathSegment(repo.owner)}/${pathSegment(repo.name)}/pulls" +
                    "?state=open&per_page=$PULL_REQUEST_LIMIT&head=${queryValue("${repo.owner}:$branch")}",
            ),
            headers(token),
        )

    override fun latestRun(repo: ForgeRepo, branch: String, token: String): ForgeRequest =
        ForgeRequest(
            URI.create(
                "${base(repo.host)}/repos/${pathSegment(repo.owner)}/${pathSegment(repo.name)}/actions/runs" +
                    "?branch=${queryValue(branch)}&per_page=1",
            ),
            headers(token),
        )

    override fun parsePullRequests(body: String): ForgeAnswer<List<ForgePullRequest>> =
        decodeForge(body, ListSerializer(GhPull.serializer())) { pulls -> pulls.map { it.toModel() } }

    override fun parseLatestRun(body: String): ForgeAnswer<ForgeRun?> =
        decodeForge(body, GhRuns.serializer()) { runs -> runs.workflowRuns.firstOrNull()?.toModel() }

    /**
     * `https://api.github.com` for the public service; `https://HOST/api/v3` for GitHub Enterprise Server,
     * which is the form GHE's own reference documents.
     */
    private fun base(host: String): String =
        if (host.equals(DOT_COM, ignoreCase = true)) "https://api.github.com" else "https://$host/api/v3"

    /**
     * `User-Agent` is REQUIRED and its absence is invisible on github.com.
     *
     * GitHub's docs say every API request must carry one and that a request without it is rejected — with a
     * 403, which this package reads as "the token cannot see it" and turns into no card. The reason it is not
     * caught in testing is that the JDK's HTTP client supplies a default (`Java-http-client/…`) which
     * **api.github.com accepts and GitHub Enterprise Server does not**: the feature then works for everyone
     * on the public service and silently does nothing for exactly the users who run their own.
     */
    private fun headers(token: String): Map<String, String> = mapOf(
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to API_VERSION,
        "User-Agent" to ForgeHttp.USER_AGENT,
        "Authorization" to "Bearer $token",
    )

    private fun GhPull.toModel() = ForgePullRequest(
        number = number,
        title = title,
        url = htmlUrl,
        state = state,
        draft = draft,
        author = user?.login?.ifBlank { null },
    )

    /**
     * Null when the run's state is a word this build does not know.
     *
     * **Dropping the run is the point.** The card is a single indicator, so an unrecognised state has to be
     * rendered as *some* colour, and every available choice is a lie: green on a new failure mode, red on a
     * new benign one. GitHub adds conclusion values over time, and a state we guessed is indistinguishable on
     * screen from one we read — the same reason the session-diff review refuses a hunk it cannot match rather
     * than reconstructing a plausible one.
     */
    private fun GhRun.toModel(): ForgeRun? {
        val state = statusOf(status, conclusion) ?: return null
        return ForgeRun(
            name = name?.ifBlank { null },
            status = state,
            url = htmlUrl,
            // `updated_at` on a live run means "last touched", not "finished" — see ForgeRun.finishedAtIso.
            finishedAtIso = updatedAt?.takeIf { state != ForgeRunStatus.RUNNING },
        )
    }

    private fun statusOf(status: String?, conclusion: String?): ForgeRunStatus? = when {
        status in IN_FLIGHT -> ForgeRunStatus.RUNNING
        status != "completed" -> null
        conclusion in NOT_FAILING -> ForgeRunStatus.COMPLETED
        conclusion in FAILING -> ForgeRunStatus.FAILED
        conclusion in ABANDONED -> ForgeRunStatus.STOPPED
        else -> null
    }
}

// --- Wire shapes ------------------------------------------------------------------------------------------
//
// Every field carries a default. A response missing one must degrade to an unremarkable card rather than to
// `Silent(MALFORMED)`: the parse is also the gate on a body that is not this API at all, and a gate that
// fires on an absent optional field cannot be trusted when it fires on a proxy's error page.

@Serializable
private data class GhPull(
    val number: Long = 0,
    val title: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val state: String = "open",
    val draft: Boolean = false,
    val user: GhUser? = null,
)

@Serializable
private data class GhUser(val login: String = "")

@Serializable
private data class GhRuns(
    @SerialName("workflow_runs") val workflowRuns: List<GhRun> = emptyList(),
)

@Serializable
private data class GhRun(
    val name: String? = null,
    val status: String? = null,
    val conclusion: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("updated_at") val updatedAt: String? = null,
)
