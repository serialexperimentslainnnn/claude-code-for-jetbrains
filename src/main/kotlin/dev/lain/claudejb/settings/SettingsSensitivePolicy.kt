package dev.lain.claudejb.settings

import dev.lain.claudejb.permission.CredentialPaths
import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.permission.SensitiveGuard
import dev.lain.claudejb.session.RemoteMounts
import kotlinx.serialization.json.JsonObject

fun ClaudeSettings.sensitiveGlobs(): List<String> {
    val extra = state.sensitiveExtraGlobs.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
    return CredentialPaths.SENSITIVE_GLOBS + extra
}

fun ClaudeSettings.sensitiveDecision(
    input: JsonObject,
    projectRoot: String?,
): SensitiveGuard.Decision {
    val decision = SensitiveGuard.evaluate(input, sensitivePolicy(projectRoot))
    if (decision.verdict == SensitiveGuard.Verdict.ALLOW || !guardSuspended()) return decision
    val what = decision.detail?.let { " — it $it" }.orEmpty()
    return SensitiveGuard.Decision(
        SensitiveGuard.Verdict.ALLOW,
        "${decision.rule?.label ?: "A guard rule"} matched$what — allowed because the Sensitive Guard is disabled",
        decision.rule,
        decision.detail,
    )
}

fun ClaudeSettings.guardSuspended(): Boolean =
    SecuritySuspensions.guardSuspended(state, System.currentTimeMillis())

fun ClaudeSettings.guardMode(): GuardMode = GuardMode.from(state.guardMode) ?: GuardMode.DEFAULT

fun ClaudeSettings.sensitivePolicy(projectRoot: String?): SensitiveGuard.Policy {
    val snap = RemoteMounts.snapshot()
    val env = parseEnv()
    return SensitiveGuard.Policy(
        globs = sensitiveGlobs(),
        home = System.getProperty("user.home"),
        currentUser = System.getProperty("user.name"),
        guardedRoots = snap.remoteRoots,
        wslHost = snap.isWsl,
        projectRoot = projectRoot,
        pathResolver = { raw -> runCatching { java.io.File(raw).canonicalPath }.getOrNull() },
        envValues = launchEnvValues(env),
        fileReader = ::readForAnalysis,
        permissiveRules = permissiveRules(),
        httpProxy = env.proxyValue("http_proxy"),
        httpsProxy = env.proxyValue("https_proxy"),
        noProxyHosts = env.proxyValue("no_proxy").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
        extraBlockedDomains = extraBlockedDomains(),
        commandWhitelist = commandWhitelist(),
        categoryWhitelist = GuardWhitelists.byCategory(state.securityCategoryWhitelists),
        ruleWhitelist = GuardWhitelists.byRule(state.securityRuleWhitelists),
    )
}

internal fun ClaudeSettings.permissiveRules(): Set<SecurityRule> {
    if (guardMode() == GuardMode.PERMISSIVE) return SecurityRule.entries.toSet()
    val perRule = state.disabledSecurityRules.split(',').mapNotNull { SecurityRule.from(it.trim()) }
    val timed = SecuritySuspensions.active(state.securityRuleSuspensions, System.currentTimeMillis())
    return perRule.toSet() + timed + SecuritySuspensions.sessionSuspended()
}

internal fun ClaudeSettings.extraBlockedDomains(): List<String> =
    state.securityExtraBlockedDomains.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }

internal fun ClaudeSettings.commandWhitelist(): List<String> =
    GuardWhitelists.commands(state.securityCommandWhitelist)

private fun launchEnvValues(settingsEnv: Map<String, String>): Map<String, String> =
    System.getenv() + settingsEnv

private fun readForAnalysis(path: String): String? = runCatching {
    val file = java.io.File(path)
    if (!file.isFile || file.length() > MAX_ANALYSIS_BYTES) return@runCatching null
    file.readText()
}.getOrNull()

private const val MAX_ANALYSIS_BYTES = 512L * 1024

private fun Map<String, String>.proxyValue(lowerName: String): String? {
    val fromSettings = entries.firstOrNull { it.key.equals(lowerName, ignoreCase = true) }?.value
    val value = fromSettings?.takeIf { it.isNotBlank() }
        ?: System.getenv(lowerName)?.takeIf { it.isNotBlank() }
        ?: System.getenv(lowerName.uppercase())?.takeIf { it.isNotBlank() }
    return value?.trim()
}
