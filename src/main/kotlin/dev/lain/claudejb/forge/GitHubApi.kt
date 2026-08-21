package dev.lain.claudejb.forge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI

internal object GitHubApi : ForgeApi {

    private const val API_VERSION = "2022-11-28"

    private const val DOT_COM = "github.com"

    private const val PULL_REQUEST_LIMIT = 20

    private const val RUN_LIMIT = 20

    private val IN_FLIGHT = setOf("queued", "in_progress", "waiting", "requested", "pending")

    private val NOT_FAILING = setOf("success", "neutral")

    private val FAILING = setOf("failure", "timed_out", "action_required", "startup_failure")

    private val ABANDONED = setOf("cancelled", "skipped", "stale")

    override fun pullRequests(repo: ForgeRepo, branch: String, token: String): ForgeRequest =
        ForgeRequest(
            URI.create(
                "${base(repo.host)}/repos/${pathSegment(repo.owner)}/${pathSegment(repo.name)}/pulls" +
                    "?state=open&per_page=$PULL_REQUEST_LIMIT&sort=updated&direction=desc" +
                    if (branch.isBlank()) "" else "&head=${queryValue("${repo.owner}:$branch")}",
            ),
            headers(token),
        )

    override fun runs(repo: ForgeRepo, branch: String, token: String): ForgeRequest =
        ForgeRequest(
            URI.create(
                "${base(repo.host)}/repos/${pathSegment(repo.owner)}/${pathSegment(repo.name)}/actions/runs" +
                    "?branch=${queryValue(branch)}&per_page=$RUN_LIMIT",
            ),
            headers(token),
        )

    override fun parsePullRequests(body: String): ForgeAnswer<List<ForgePullRequest>> =
        decodeForge(body, ListSerializer(GhPull.serializer())) { pulls -> pulls.map { it.toModel() } }

    override fun access(repo: ForgeRepo, token: String): ForgeRequest =
        ForgeRequest(
            URI.create("${base(repo.host)}/repos/${pathSegment(repo.owner)}/${pathSegment(repo.name)}"),
            headers(token),
        )

    override fun parseAccess(body: String): ForgeAnswer<ForgeAccessLevel> =
        decodeForge(body, GhRepo.serializer()) { repo ->
            val rights = repo.permissions
            when {
                rights == null -> ForgeAccessLevel.READ
                rights.admin || rights.maintain -> ForgeAccessLevel.ADMIN
                rights.push -> ForgeAccessLevel.WRITE
                rights.pull || rights.triage -> ForgeAccessLevel.READ
                else -> ForgeAccessLevel.NONE
            }
        }

    override fun viewer(repo: ForgeRepo, token: String): ForgeRequest =
        ForgeRequest(URI.create("${base(repo.host)}/user"), headers(token))

    override fun parseViewer(body: String): ForgeAnswer<String?> =
        decodeForge(body, GhUser.serializer()) { it.login.ifBlank { null } }

    override fun approve(repo: ForgeRepo, number: Long, token: String): ForgeRequest = ForgeRequest(
        pullUri(repo, number, "/reviews"),
        jsonHeaders(token),
        method = "POST",
        body = buildJsonObject { put("event", "APPROVE") }.toString(),
    )

    override fun unapprove(repo: ForgeRepo, number: Long, token: String): ForgeRequest? = null

    override fun merge(repo: ForgeRepo, number: Long, token: String): ForgeRequest =
        ForgeRequest(pullUri(repo, number, "/merge"), headers(token), method = "PUT")

    override fun comment(repo: ForgeRepo, number: Long, text: String, token: String): ForgeRequest = ForgeRequest(
        URI.create("${repoBase(repo)}/issues/$number/comments"),
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
        URI.create("${repoBase(repo)}/pulls"),
        jsonHeaders(token),
        method = "POST",
        body = buildJsonObject {
            put("head", source)
            put("base", target)
            put("title", title)
        }.toString(),
    )

    private fun repoBase(repo: ForgeRepo): String =
        "${base(repo.host)}/repos/${pathSegment(repo.owner)}/${pathSegment(repo.name)}"

    private fun pullUri(repo: ForgeRepo, number: Long, suffix: String): URI =
        URI.create("${repoBase(repo)}/pulls/$number$suffix")

    private fun jsonHeaders(token: String): Map<String, String> =
        headers(token) + ("Content-Type" to "application/json")

    override fun retryRun(repo: ForgeRepo, runId: Long, token: String): ForgeRequest =
        runAction(repo, runId, "rerun", token)

    override fun cancelRun(repo: ForgeRepo, runId: Long, token: String): ForgeRequest =
        runAction(repo, runId, "cancel", token)

    private fun runAction(repo: ForgeRepo, runId: Long, verb: String, token: String) = ForgeRequest(
        URI.create(
            "${base(repo.host)}/repos/${pathSegment(repo.owner)}/${pathSegment(repo.name)}" +
                "/actions/runs/$runId/$verb",
        ),
        headers(token),
        method = "POST",
    )

    override fun parseRuns(body: String): ForgeAnswer<List<ForgeRun>> =
        decodeForge(body, GhRuns.serializer()) { runs -> runs.workflowRuns.mapNotNull { it.toModel() } }

    private fun base(host: String): String =
        if (host.equals(DOT_COM, ignoreCase = true)) "https://api.github.com" else "https://$host/api/v3"

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
        sourceBranch = head?.ref?.ifBlank { null },
        targetBranch = base?.ref?.ifBlank { null },
    )

    private fun GhRun.toModel(): ForgeRun? {
        val state = statusOf(status, conclusion) ?: return null
        return ForgeRun(
            id = id,
            name = name?.ifBlank { null },
            status = state,
            url = htmlUrl,
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

@Serializable
private data class GhPull(
    val number: Long = 0,
    val title: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val state: String = "open",
    val draft: Boolean = false,
    val user: GhUser? = null,
    val head: GhRef? = null,
    val base: GhRef? = null,
)

@Serializable
private data class GhUser(val login: String = "")

@Serializable
private data class GhRepo(val permissions: GhPermissions? = null)

@Serializable
private data class GhPermissions(
    val admin: Boolean = false,
    val maintain: Boolean = false,
    val push: Boolean = false,
    val triage: Boolean = false,
    val pull: Boolean = false,
)

@Serializable
private data class GhRef(val ref: String = "")

@Serializable
private data class GhRuns(
    @SerialName("workflow_runs") val workflowRuns: List<GhRun> = emptyList(),
)

@Serializable
private data class GhRun(
    val id: Long = 0,
    val name: String? = null,
    val status: String? = null,
    val conclusion: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("updated_at") val updatedAt: String? = null,
)
