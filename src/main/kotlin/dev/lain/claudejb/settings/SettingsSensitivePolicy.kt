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

/**
 * The guard's answer for one tool call, with **Allow All** applied on top of it.
 *
 * Allow All is applied here rather than inside the guard, and that placement is the point: `SensitiveGuard`
 * keeps having exactly one behaviour, and the thing that overrides it is a switch in the user's own UI.
 *
 * The evaluation still runs while Allow All is on, and that is deliberate: the call is permitted either way,
 * but knowing WHICH rule it would have tripped is what lets the transcript say so instead of staying silent.
 * A hit becomes an ALLOW that still carries its rule and a reason — the shape the whitelist lift already
 * uses — and the two are told apart by the reason, because they are two different bypasses.
 *
 * The audit of the user's own environment script goes through [sensitivePolicy] directly and is therefore
 * NOT covered: it judges a file the user configured, before the process starts.
 */
fun ClaudeSettings.sensitiveDecision(
    input: JsonObject,
    projectRoot: String?,
): SensitiveGuard.Decision {
    val decision = SensitiveGuard.evaluate(input, sensitivePolicy(projectRoot))
    if (decision.verdict == SensitiveGuard.Verdict.ALLOW || !guardSuspended()) return decision
    // Which rule, what it saw, and why it ran anyway — in that order, because a warning that names only the
    // switch leaves the reader guessing at the thing the switch let past.
    val what = decision.detail?.let { " — it $it" }.orEmpty()
    return SensitiveGuard.Decision(
        SensitiveGuard.Verdict.ALLOW,
        "${decision.rule?.label ?: "A guard rule"} matched$what — allowed because the Sensitive Guard is disabled",
        decision.rule,
        decision.detail,
    )
}

/** True while the shield is down — the **Allow All** bypass. */
fun ClaudeSettings.guardSuspended(): Boolean =
    SecuritySuspensions.guardSuspended(state, System.currentTimeMillis())

/** The guard's own mode, which every Enforcing rule defers to. */
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

/**
 * Every rule currently running in **Permissive** mode.
 *
 * Four sources, unioned: the guard's own mode — which puts the whole catalogue in Permissive when the user
 * sets it there — plus the rules set to Permissive one by one, the ones on a timed suspension, and the ones
 * suspended until the IDE closes.
 */
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
