package dev.lain.claudejb.forge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI

internal object GitLabApi : ForgeApi {

    private const val MERGE_REQUEST_LIMIT = 20

    private const val PIPELINE_LIMIT = 20

    private const val GUEST = 10

    private const val DEVELOPER = 30

    private const val MAINTAINER = 40

    private const val JOB_LIMIT = 50

    private const val COMMENT_LIMIT = 50

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

    override fun approve(repo: ForgeRepo, number: Long, token: String): ForgeRequest =
        ForgeRequest(mergeRequestUri(repo, number, "/approve"), headers(token), method = "POST")

    override fun unapprove(repo: ForgeRepo, number: Long, token: String): ForgeRequest =
        ForgeRequest(mergeRequestUri(repo, number, "/unapprove"), headers(token), method = "POST")

    override fun merge(repo: ForgeRepo, number: Long, token: String): ForgeRequest =
        ForgeRequest(mergeRequestUri(repo, number, "/merge"), headers(token), method = "PUT")

    override fun comment(repo: ForgeRepo, number: Long, text: String, token: String): ForgeRequest =
        ForgeRequest(
            mergeRequestUri(repo, number, "/notes"),
            jsonHeaders(token),
            method = "POST",
            body = buildJsonObject { put("body", text) }.toString(),
        )

    override fun openPullRequest(
        repo: ForgeRepo,
        source: String,
        target: String,
        title: String,
        token: String,
    ): ForgeRequest = ForgeRequest(
        URI.create("${base(repo.host)}/projects/${pathSegment(repo.path)}/merge_requests"),
        jsonHeaders(token),
        method = "POST",
        body = buildJsonObject {
            put("source_branch", source)
            put("target_branch", target)
            put("title", title)
        }.toString(),
    )

    private fun mergeRequestUri(repo: ForgeRepo, number: Long, suffix: String): URI =
        URI.create("${base(repo.host)}/projects/${pathSegment(repo.path)}/merge_requests/$number$suffix")

    private fun jsonHeaders(token: String): Map<String, String> =
        headers(token) + ("Content-Type" to "application/json")

    override fun comments(repo: ForgeRepo, number: Long, token: String): ForgeRequest = ForgeRequest(
        URI.create("${mergeRequestUri(repo, number, "/notes")}?per_page=$COMMENT_LIMIT&sort=asc"),
        headers(token),
    )

    override fun parseComments(body: String): ForgeAnswer<List<String>> =
        decodeForge(body, ListSerializer(GlNote.serializer())) { notes ->
            notes.filterNot { it.system }.mapNotNull { it.body?.trim()?.ifBlank { null } }
        }

    override fun jobs(repo: ForgeRepo, runId: Long, token: String): ForgeRequest = ForgeRequest(
        URI.create("${base(repo.host)}/projects/${pathSegment(repo.path)}/pipelines/$runId/jobs?per_page=$JOB_LIMIT"),
        headers(token),
    )

    override fun parseJobs(body: String): ForgeAnswer<List<ForgeJob>> =
        decodeForge(body, ListSerializer(GlJob.serializer())) { jobs ->
            jobs.map { ForgeJob(it.id, it.name?.ifBlank { null }, it.status == "failed") }
        }

    override fun jobLog(repo: ForgeRepo, jobId: Long, token: String): ForgeRequest = ForgeRequest(
        URI.create("${base(repo.host)}/projects/${pathSegment(repo.path)}/jobs/$jobId/trace"),
        headers(token),
    )

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
        targetBranch = targetBranch?.ifBlank { null },
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
    @SerialName("target_branch") val targetBranch: String? = null,
)

@Serializable
private data class GlUser(val username: String = "")

@Serializable
private data class GlNote(val body: String? = null, val system: Boolean = false)

@Serializable
private data class GlJob(val id: Long = 0, val name: String? = null, val status: String? = null)

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
