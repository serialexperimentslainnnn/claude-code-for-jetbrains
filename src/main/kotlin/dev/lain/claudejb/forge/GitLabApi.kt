package dev.lain.claudejb.forge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.net.URI

internal object GitLabApi : ForgeApi {

    private const val MERGE_REQUEST_LIMIT = 20

    private const val PIPELINE_LIMIT = 20

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

    private val ABANDONED = setOf("canceling", "canceled", "skipped")

    override fun pullRequests(repo: ForgeRepo, branch: String, token: String): ForgeRequest =
        ForgeRequest(
            URI.create(
                "${base(repo.host)}/projects/${pathSegment(repo.path)}/merge_requests" +
                    "?state=opened&per_page=$MERGE_REQUEST_LIMIT&order_by=updated_at&sort=desc" +
                    if (branch.isBlank()) "" else "&source_branch=${queryValue(branch)}",
            ),
            headers(token),
        )

    override fun runs(repo: ForgeRepo, branch: String, token: String): ForgeRequest =
        ForgeRequest(
            URI.create(
                "${base(repo.host)}/projects/${pathSegment(repo.path)}/pipelines" +
                    "?ref=${queryValue(branch)}&per_page=$PIPELINE_LIMIT",
            ),
            headers(token),
        )

    override fun parsePullRequests(body: String): ForgeAnswer<List<ForgePullRequest>> =
        decodeForge(body, ListSerializer(GlMergeRequest.serializer())) { mrs -> mrs.map { it.toModel() } }

    override fun parseRuns(body: String): ForgeAnswer<List<ForgeRun>> =
        decodeForge(body, ListSerializer(GlPipeline.serializer())) { page -> page.mapNotNull { it.toModel() } }

    private fun base(host: String): String = "https://$host/api/v4"

    private fun headers(token: String): Map<String, String> = mapOf(
        "User-Agent" to ForgeHttp.USER_AGENT,
        "PRIVATE-TOKEN" to token,
    )

    private fun GlMergeRequest.toModel() = ForgePullRequest(
        number = iid,
        title = title,
        url = webUrl,
        state = if (state == "opened") "open" else state,
        draft = draft,
        author = author?.username?.ifBlank { null },
        sourceBranch = sourceBranch?.ifBlank { null },
    )

    private fun GlPipeline.toModel(): ForgeRun? {
        val state = statusOf(status) ?: return null
        return ForgeRun(
            name = name?.ifBlank { null },
            status = state,
            url = webUrl,
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

@Serializable
private data class GlMergeRequest(
    val iid: Long = 0,
    val title: String = "",
    @SerialName("web_url") val webUrl: String = "",
    val state: String = "opened",
    val draft: Boolean = false,
    val author: GlUser? = null,
    @SerialName("source_branch") val sourceBranch: String? = null,
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
