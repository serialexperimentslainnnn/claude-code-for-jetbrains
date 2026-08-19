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
): SensitiveGuard.Decision = SensitiveGuard.evaluate(input, sensitivePolicy(projectRoot))

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
        disabledRules = disabledSecurityRules(),
        httpProxy = env.proxyValue("http_proxy"),
        httpsProxy = env.proxyValue("https_proxy"),
        noProxyHosts = env.proxyValue("no_proxy").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
        extraBlockedDomains = extraBlockedDomains(),
        commandWhitelist = commandWhitelist(),
    )
}

internal fun ClaudeSettings.disabledSecurityRules(): Set<SecurityRule> {
    val permanent = state.disabledSecurityRules.split(',').mapNotNull { SecurityRule.from(it.trim()) }
    val timed = SecuritySuspensions.active(state.securityRuleSuspensions, System.currentTimeMillis())
    return permanent.toSet() + timed + SecuritySuspensions.sessionSuspended()
}

internal fun ClaudeSettings.approvedGuardCommands(): String = state.securityCommandApprovals

internal fun ClaudeSettings.extraBlockedDomains(): List<String> =
    state.securityExtraBlockedDomains.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }

internal fun ClaudeSettings.commandWhitelist(): List<String> =
    state.securityCommandWhitelist.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }

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
