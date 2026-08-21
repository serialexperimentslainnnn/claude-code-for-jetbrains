package dev.lain.claudejb.forge

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal class ForgeRequest(
    val uri: URI,
    val headers: Map<String, String>,
    val method: String = "GET",
    val body: String? = null,
) {

    override fun toString(): String = "ForgeRequest(uri=$uri)"
}

internal interface ForgeApi {

    fun pullRequests(repo: ForgeRepo, branch: String, token: String): ForgeRequest

    fun runs(repo: ForgeRepo, branch: String, token: String): ForgeRequest

    fun parsePullRequests(body: String): ForgeAnswer<List<ForgePullRequest>>

    fun parseRuns(body: String): ForgeAnswer<List<ForgeRun>>

    fun retryRun(repo: ForgeRepo, runId: Long, token: String): ForgeRequest

    fun cancelRun(repo: ForgeRepo, runId: Long, token: String): ForgeRequest
}

internal fun apiFor(provider: ForgeProvider): ForgeApi = when (provider) {
    ForgeProvider.GITHUB -> GitHubApi
    ForgeProvider.GITLAB -> GitLabApi
}

internal val ForgeJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

internal fun <W, T> decodeForge(body: String, serializer: KSerializer<W>, map: (W) -> T): ForgeAnswer<T> =
    runCatching { map(ForgeJson.decodeFromString(serializer, body)) }
        .fold(
            onSuccess = { mapped -> ForgeAnswer.Known(mapped) },
            onFailure = { ForgeAnswer.Silent(ForgeSilence.MALFORMED) },
        )

internal fun isUsableHost(host: String): Boolean =
    host.length <= MAX_HOST_LENGTH && HOSTNAME.matches(host)

private const val MAX_HOST_LENGTH = 253

private val HOSTNAME =
    Regex("""[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*(?::\d{1,5})?""")

internal fun pathSegment(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

internal fun queryValue(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
