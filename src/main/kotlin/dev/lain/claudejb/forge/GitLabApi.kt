package dev.lain.claudejb.forge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.net.URI

internal object GitLabApi : ForgeApi {

    private const val MERGE_REQUEST_LIMIT = 20

    private const val PIPELINE_LIMIT = 20

    private const val GUEST = 10

    private const val DEVELOPER = 30

    private const val MAINTAINER = 40

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

    override fun access(repo: ForgeRepo, token: String): ForgeRequest =
        ForgeRequest(URI.create("${base(repo.host)}/projects/${pathSegment(repo.path)}"), headers(token))

    override fun parseAccess(body: String): ForgeAnswer<ForgeAccessLevel> =
        decodeForge(body, GlProject.serializer()) { project ->
            levelOf(
                maxOf(
                    project.permissions?.projectAccess?.accessLevel ?: 0,
                    project.permissions?.groupAccess?.accessLevel ?: 0,
                ),
            )
        }

    override fun viewer(repo: ForgeRepo, token: String): ForgeRequest =
        ForgeRequest(URI.create("${base(repo.host)}/user"), headers(token))

    override fun parseViewer(body: String): ForgeAnswer<String?> =
        decodeForge(body, GlUser.serializer()) { it.username.ifBlank { null } }

    private fun levelOf(accessLevel: Int): ForgeAccessLevel = when {
        accessLevel >= MAINTAINER -> ForgeAccessLevel.ADMIN
        accessLevel >= DEVELOPER -> ForgeAccessLevel.WRITE
        accessLevel >= GUEST -> ForgeAccessLevel.READ
        else -> ForgeAccessLevel.NONE
    }

    override fun retryRun(repo: ForgeRepo, runId: Long, token: String): ForgeRequest =
        pipelineAction(repo, runId, "retry", token)

    override fun cancelRun(repo: ForgeRepo, runId: Long, token: String): ForgeRequest =
        pipelineAction(repo, runId, "cancel", token)

    private fun pipelineAction(repo: ForgeRepo, runId: Long, verb: String, token: String) = ForgeRequest(
        URI.create("${base(repo.host)}/projects/${pathSegment(repo.path)}/pipelines/$runId/$verb"),
        headers(token),
        method = "POST",
    )

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
            id = id,
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
private data class GlProject(val permissions: GlPermissions? = null)

@Serializable
private data class GlPermissions(
    @SerialName("project_access") val projectAccess: GlAccess? = null,
    @SerialName("group_access") val groupAccess: GlAccess? = null,
)

@Serializable
private data class GlAccess(@SerialName("access_level") val accessLevel: Int = 0)

@Serializable
private data class GlPipeline(
    val id: Long = 0,
    val name: String? = null,
    val status: String? = null,
    @SerialName("web_url") val webUrl: String = "",
    @SerialName("updated_at") val updatedAt: String? = null,
)
