package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

object ProxyRules {

    private val ALT_PROXY_FLAG = Regex(
        """(?:-x|--proxy)[\s=](\S+)""" +
            """|-c\s+https?\.proxy=(\S*)""" +
            """|--https?-proxy[\s=](\S+)""",
        RegexOption.IGNORE_CASE,
    )

    private val INLINE_ENV_PROXY = Regex(
        """(?:^|[\s;&|])(?:https?_proxy|all_proxy)=(\S*)""",
        RegexOption.IGNORE_CASE,
    )

    private val NO_PROXY_FLAG = Regex("""--noproxy[\s=](\S+)""", RegexOption.IGNORE_CASE)

    private val WGET_DISABLE = Regex("""-e\s+use_proxy=no\b""", RegexOption.IGNORE_CASE)

    internal fun proxyHit(input: JsonObject, policy: SensitiveGuard.Policy): String? {
        if (policy.httpProxy.isNullOrBlank() && policy.httpsProxy.isNullOrBlank()) return null
        for (raw in ToolInputScanner.commandCandidates(input)) {
            val command = CommandRules.deobfuscate(raw, policy.home, policy.envValues)
            explicitBypass(command, policy)?.let { return it }
            alternateProxy(command, policy)?.let { return it }
        }
        return null
    }

    private fun declaredProxies(policy: SensitiveGuard.Policy): Set<String> =
        setOfNotNull(policy.httpProxy, policy.httpsProxy).map { it.trim().lowercase().trimEnd('/') }.toSet()

    private fun alternateProxy(command: String, policy: SensitiveGuard.Policy): String? {
        val declared = declaredProxies(policy)
        ALT_PROXY_FLAG.findAll(command).forEach { m ->
            val value = m.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return@forEach
            val norm = value.trim().lowercase().trimEnd('/')
            if (norm.isNotBlank() && norm !in declared) return m.value.trim()
        }
        INLINE_ENV_PROXY.findAll(command).forEach { m ->
            val value = m.groupValues[1].trim()
            if (value.isBlank()) return@forEach
            if (value.lowercase().trimEnd('/') !in declared) return m.value.trim()
        }
        return null
    }

    private fun explicitBypass(command: String, policy: SensitiveGuard.Policy): String? {
        val exempt = policy.noProxyHosts.map { it.trim().lowercase() }.filter { it.isNotBlank() }
        NO_PROXY_FLAG.find(command)?.let { m ->
            val targets = m.groupValues[1].split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }
            val notExempt = targets.any { t -> exempt.none { host -> t == host || t.endsWith(".$host") } }
            if (targets.isNotEmpty() && notExempt) return m.value.trim()
        }
        WGET_DISABLE.find(command)?.let { return it.value.trim() }
        INLINE_ENV_PROXY.findAll(command).firstOrNull { it.groupValues[1].isBlank() }?.let { return it.value.trim() }
        return null
    }
}
