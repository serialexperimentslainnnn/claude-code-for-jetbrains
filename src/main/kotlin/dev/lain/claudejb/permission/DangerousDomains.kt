package dev.lain.claudejb.permission

object DangerousDomains {

    val BLOCKED_DOMAINS: Set<String> = setOf(
        "pastebin.com", "paste.ee", "hastebin.com", "ix.io",
        "dpaste.com", "rentry.co", "controlc.com", "termbin.com", "sprunge.us", "privatebin.net",
        "transfer.sh", "file.io", "gofile.io", "0x0.st",
        "bashupload.com", "temp.sh", "oshi.at", "catbox.moe", "x0.at", "filebin.net", "ufile.io",
        "webhook.site", "requestbin.com", "beeceptor.com", "pipedream.net",
        "interact.sh", "oastify.com", "burpcollaborator.net", "canarytokens.com",
        "oast.pro", "oast.live", "oast.site", "oast.online", "oast.fun", "hookbin.com", "smee.io",
        "ngrok.io", "ngrok-free.app", "ngrok.app", "ngrok.dev",
        "trycloudflare.com", "serveo.net", "localhost.run", "loca.lt", "pagekite.me",
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
