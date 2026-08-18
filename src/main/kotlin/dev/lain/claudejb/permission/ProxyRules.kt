package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

/**
 * [SecurityRule.PROXY_BYPASS] — **egress through a declared proxy only**: once the user has named an
 * `HTTP_PROXY`/`HTTPS_PROXY` (optionally with `NO_PROXY` exceptions), a command that names a DIFFERENT proxy
 * or explicitly asks to bypass the declared one is worth a card.
 *
 * ### Only when a proxy is actually declared
 * With neither [SensitiveGuard.Policy.httpProxy] nor [SensitiveGuard.Policy.httpsProxy] set, this rule never
 * fires — there is nothing declared to be routed around, and "you must use a proxy" is not a claim this guard
 * makes on its own. This is a **data gate**, not [SecurityRule.PROXY_BYPASS]' switch: the switch decides the
 * OUTCOME of a hit (DENY or a card), this decides whether there is anything to detect at all — the same
 * distinction [SensitiveGuard.Policy.wslHost] draws for the WSL rule.
 *
 * ### What is heuristic here, and said plainly
 * This is command-TEXT pattern matching against the flags of a handful of common tools (`curl`, `wget`, `git`,
 * `npm`/`yarn`, `pip`) and the shell's own `*_proxy` environment-variable convention — it cannot see an
 * application's own internal proxy handling, a custom HTTP client's configuration file, or a language runtime
 * that reads the proxy from somewhere this never looks. Closing that is a matter of widening the patterns,
 * never of trusting a caller — the same limitation [SensitiveGuard]'s own class doc states for every rule that
 * reads shell text rather than a structured field.
 */
object ProxyRules {

    /** A flag that NAMES an alternate proxy: `curl -x`/`--proxy`, `wget --proxy=`, `git -c http(s).proxy=`,
     *  `npm`/`yarn --proxy=`/`--https-proxy=`, `pip --proxy`. One capture group is populated per alternative. */
    private val ALT_PROXY_FLAG = Regex(
        """(?:-x|--proxy)[\s=](\S+)""" +
            """|-c\s+https?\.proxy=(\S*)""" +
            """|--https?-proxy[\s=](\S+)""",
        RegexOption.IGNORE_CASE,
    )

    /** A shell-assignment prefix setting the proxy for the command it precedes, or clearing it (`http_proxy=`
     *  with nothing after `=`, which is itself a bypass — see [explicitBypass]). */
    private val INLINE_ENV_PROXY = Regex(
        """(?:^|[\s;&|])(?:https?_proxy|all_proxy)=(\S*)""",
        RegexOption.IGNORE_CASE,
    )

    /** `curl --noproxy <hosts>`: an explicit list of destinations that should skip the declared proxy. */
    private val NO_PROXY_FLAG = Regex("""--noproxy[\s=](\S+)""", RegexOption.IGNORE_CASE)

    /** `wget -e use_proxy=no`: wget's own bypass switch. */
    private val WGET_DISABLE = Regex("""-e\s+use_proxy=no\b""", RegexOption.IGNORE_CASE)

    internal fun proxyHit(input: JsonObject, policy: SensitiveGuard.Policy): String? {
        if (policy.httpProxy.isNullOrBlank() && policy.httpsProxy.isNullOrBlank()) return null
        for (raw in ToolInputScanner.commandCandidates(input)) {
            val command = CommandRules.deobfuscate(raw)
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
            if (value.isBlank()) return@forEach // an empty assignment is a bypass, handled below
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
