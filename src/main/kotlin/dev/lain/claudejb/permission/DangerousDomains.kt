package dev.lain.claudejb.permission

object DangerousDomains {

    val BLOCKED_DOMAINS: Set<String> = setOf(
        "pastebin.com", "paste.ee", "hastebin.com", "ix.io",
        "transfer.sh", "file.io", "gofile.io", "0x0.st",
        "webhook.site", "requestbin.com", "beeceptor.com", "pipedream.net",
        "interact.sh", "oastify.com", "burpcollaborator.net", "canarytokens.com",
        "ngrok.io", "ngrok-free.app",
    )

    internal fun blockedHit(urls: List<String>, extra: List<String>): String? {
        if (urls.isEmpty()) return null
        val domains = BLOCKED_DOMAINS + extra.mapNotNull(::normalizeDomain)
        return urls.asSequence().mapNotNull(::host).firstOrNull { h -> domains.any { matches(h, it) } }
    }

    fun host(url: String): String? {
        val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
        if (afterScheme.isEmpty()) return null
        val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        val hostAndPort = authority.substringAfterLast('@')
        val bare = if (hostAndPort.startsWith("[")) {
            hostAndPort.substringAfter('[').substringBefore(']')
        } else {
            hostAndPort.substringBefore(':')
        }
        return bare.trim().trimEnd('.').lowercase().ifBlank { null }
    }

    private fun matches(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    private fun normalizeDomain(raw: String): String? = host(raw.trim())
        ?: raw.trim().removePrefix("*.").removePrefix(".").trimEnd('/').trimEnd('.').lowercase().ifBlank { null }
}
