package dev.lain.claudejb.forge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.net.URI

/**
 * GitLab (SaaS or self-managed) over the v4 REST API.
 *
 * Two endpoints, both verified against the current official reference:
 *  - `GET /api/v4/projects/{urlencoded path}/merge_requests?state=opened&source_branch={branch}` — *List
 *    project merge requests*. The state word is `opened`, not `open`; `open` is not one of the accepted
 *    values and does not filter.
 *  - `GET /api/v4/projects/{urlencoded path}/pipelines?ref={branch}&per_page=1` — *List project pipelines*,
 *    which default to newest first, so one page of one is the last pipeline.
 *
 * **The project is addressed by its URL-encoded full path**, `platform%2Fbackend%2Fservices`, because a
 * numeric project id is not something a remote URL carries — see [pathSegment].
 *
 * **The list response carries no `finished_at`.** That field exists only on the single-pipeline and
 * latest-pipeline endpoints; the list gives `created_at` and `updated_at`. A second request per card to
 * recover a timestamp is not worth it, so a terminal pipeline's `updated_at` is used, which is the moment it
 * reached that state.
 */
internal object GitLabApi : ForgeApi {

    /** Enough to fill the card; a branch with more than this many open merge requests is not a real case. */
    private const val MERGE_REQUEST_LIMIT = 20

    /**
     * Pipeline states that mean "not finished".
     *
     * `manual` and `scheduled` are in here on purpose: both describe a pipeline that is waiting on something
     * (an approval, a clock) and will still run. Calling either one finished would put a terminal colour on
     * work that has not started.
     */
    private val IN_FLIGHT = setOf(
        "created",
        "waiting_for_resource",
        "preparing",
        "waiting_for_callback",
        "pending",
        "running",
        "manual",
        "scheduled",
    )

    /** `skipped` belongs here rather than with success: nothing ran, so nothing passed. */
    private val ABANDONED = setOf("canceling", "canceled", "skipped")

    override fun pullRequests(repo: ForgeRepo, branch: String, token: String): ForgeRequest =
        ForgeRequest(
            URI.create(
                "${base(repo.host)}/projects/${pathSegment(repo.path)}/merge_requests" +
                    "?state=opened&per_page=$MERGE_REQUEST_LIMIT&source_branch=${queryValue(branch)}",
            ),
            headers(token),
        )

    override fun latestRun(repo: ForgeRepo, branch: String, token: String): ForgeRequest =
        ForgeRequest(
            URI.create(
                "${base(repo.host)}/projects/${pathSegment(repo.path)}/pipelines" +
                    "?ref=${queryValue(branch)}&per_page=1",
            ),
            headers(token),
        )

    override fun parsePullRequests(body: String): ForgeAnswer<List<ForgePullRequest>> =
        decodeForge(body, ListSerializer(GlMergeRequest.serializer())) { mrs -> mrs.map { it.toModel() } }

    override fun parseLatestRun(body: String): ForgeAnswer<ForgeRun?> =
        decodeForge(body, ListSerializer(GlPipeline.serializer())) { page -> page.firstOrNull()?.toModel() }

    private fun base(host: String): String = "https://$host/api/v4"

    /**
     * GitLab's own header for a personal or project access token. It is NOT `Authorization: Bearer`, which
     * GitLab reserves for OAuth tokens and which rejects a PAT.
     */
    private fun headers(token: String): Map<String, String> = mapOf("PRIVATE-TOKEN" to token)

    private fun GlMergeRequest.toModel() = ForgePullRequest(
        // `iid` is the number the UI shows and the URL contains. `id` is globally unique and matches nothing
        // a human has ever seen.
        number = iid,
        title = title,
        url = webUrl,
        // Normalised to GitHub's spelling so one card can draw both. `work_in_progress` is deprecated in
        // favour of `draft` and is deliberately not read.
        state = if (state == "opened") "open" else state,
        draft = draft,
        author = author?.username?.ifBlank { null },
    )

    /** Null when the pipeline's state is a word this build does not know — same rule as `GitHubApi`. */
    private fun GlPipeline.toModel(): ForgeRun? {
        val state = statusOf(status) ?: return null
        return ForgeRun(
            name = name?.ifBlank { null },
            status = state,
            url = webUrl,
            // The list endpoint has no `finished_at`; on a terminal pipeline `updated_at` is when it got there.
            finishedAtIso = updatedAt?.takeIf { state != ForgeRunStatus.RUNNING },
        )
    }

    private fun statusOf(status: String?): ForgeRunStatus? = when {
        status in IN_FLIGHT -> ForgeRunStatus.RUNNING
        status == "success" -> ForgeRunStatus.COMPLETED
        status == "failed" -> ForgeRunStatus.FAILED
        status in ABANDONED -> ForgeRunStatus.STOPPED
        else -> null
    }
}

// --- Wire shapes ------------------------------------------------------------------------------------------
//
// Defaults everywhere, for the reason spelled out in GitHubApi: the decode is also the gate on a body that is
// not this API, and it cannot be both that gate and a check on optional fields.

@Serializable
private data class GlMergeRequest(
    val iid: Long = 0,
    val title: String = "",
    @SerialName("web_url") val webUrl: String = "",
    val state: String = "opened",
    val draft: Boolean = false,
    val author: GlUser? = null,
)

@Serializable
private data class GlUser(val username: String = "")

@Serializable
private data class GlPipeline(
    val name: String? = null,
    val status: String? = null,
    @SerialName("web_url") val webUrl: String = "",
    @SerialName("updated_at") val updatedAt: String? = null,
)
