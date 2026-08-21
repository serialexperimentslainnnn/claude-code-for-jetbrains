package dev.lain.claudejb.forge

import com.intellij.openapi.diagnostic.logger
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

internal object ForgeProbe {

    private val LOG = logger<ForgeProbe>()

    private val verdicts = ConcurrentHashMap<String, String>()

    private const val UNKNOWN = "unknown"

    fun detect(host: String, token: String): ForgeProvider? {
        val cached = verdicts[host]
        if (cached != null) return ForgeProvider.entries.firstOrNull { it.name == cached }
        val found = probe(host, token)
        verdicts[host] = found?.name ?: UNKNOWN
        LOG.info("Forge probe: $host is ${found?.name ?: "neither GitHub nor GitLab, or unreachable"}")
        return found
    }

    private fun probe(host: String, token: String): ForgeProvider? {
        val gitlab = mapOf(
            "User-Agent" to ForgeHttp.USER_AGENT,
            "PRIVATE-TOKEN" to token,
        )
        if (answersAt(host, "https://$host/api/v4/version", gitlab)) {
            return ForgeProvider.GITLAB
        }
        val github = mapOf(
            "Accept" to "application/vnd.github+json",
            "User-Agent" to ForgeHttp.USER_AGENT,
            "Authorization" to "Bearer $token",
        )
        if (answersAt(host, "https://$host/api/v3/meta", github)) return ForgeProvider.GITHUB
        return null
    }

    private fun answersAt(host: String, url: String, headers: Map<String, String>): Boolean =
        when (val answer = ForgeHttp.fetch(ForgeRequest(URI.create(url), headers))) {
            is ForgeAnswer.Known -> true
            is ForgeAnswer.Silent -> answer.reason == ForgeSilence.UNAUTHORIZED
        }.also { if (it) LOG.debug("Forge probe: $host answered at $url") }
}
