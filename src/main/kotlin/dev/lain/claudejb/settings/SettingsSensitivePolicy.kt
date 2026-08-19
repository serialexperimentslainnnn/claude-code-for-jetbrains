package dev.lain.claudejb.settings

import dev.lain.claudejb.permission.CredentialPaths
import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.permission.SensitiveGuard
import dev.lain.claudejb.session.RemoteMounts
import kotlinx.serialization.json.JsonObject

// How the persisted settings become the deterministic tool-call lock's SensitiveGuard.Policy. The guard
// itself is pure and lives in `permission/`; this file is only the wiring that reads the settings document
// and this host's mounts. Read FRESH on every call — a security toggle takes effect on the next tool call,
// never at the next IDE restart.

/** The active sensitive-path globs: the built-in blacklist **plus** the user's extras (additive, never less). */
fun ClaudeSettings.sensitiveGlobs(): List<String> {
    val extra = state.sensitiveExtraGlobs.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
    return CredentialPaths.SENSITIVE_GLOBS + extra
}

/**
 * The guard's deterministic verdict for a tool call (see [SensitiveGuard]) for this [projectRoot]. Enforcement
 * is per-rule configurable (Settings ▸ Claude Code ▸ Security) but never off entirely: a disabled rule still
 * downgrades to a permission card rather than a silent allow. Foreign-territory and remote-mount inputs come
 * from [RemoteMounts].
 */
fun ClaudeSettings.sensitiveDecision(
    input: JsonObject,
    projectRoot: String?,
): SensitiveGuard.Decision = SensitiveGuard.evaluate(input, sensitivePolicy(projectRoot))

/** Assembles the pure [SensitiveGuard.Policy] from settings + this host's mounts + the open project. */
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
        // Canonicalise on disk so a symlink or `..` cannot launder a path past the rules by hiding its target.
        // Off the EDT already (broker callback runs on the reader thread); a failure just leaves the literal.
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

/**
 * The rules the user switched OFF, parsed from the stored CSV.
 *
 * An unknown id is **dropped**, not guessed at, and the direction of that failure is the reason the stored set is
 * the disabled one rather than the enabled one: a garbled or stale entry can only ever fail to turn a rule off,
 * i.e. fail safe. See [SecurityRule.from].
 */
internal fun ClaudeSettings.disabledSecurityRules(): Set<SecurityRule> =
    state.disabledSecurityRules.split(',')
        .mapNotNull { SecurityRule.from(it.trim()) }
        .toSet()

/** The user's own blocked domains, one per line, `#` comments ignored — **added** to the built-in set. */
internal fun ClaudeSettings.extraBlockedDomains(): List<String> =
    state.securityExtraBlockedDomains.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }

/**
 * The exact commands the user pre-approved, one per line, `#` comments ignored.
 *
 * Same shape as [extraBlockedDomains] and the sensitive globs — a plain text block edited in Settings — and
 * deliberately so: this is the one list of the three that NARROWS the net, and giving it a different, richer
 * authoring surface (a button on a card, a "remember this" checkbox) is precisely how a pre-authorisation stops
 * being a decision taken in the cold. The parsing is the whole of the mechanism here; the fencing that decides what
 * an entry can ever lift lives in `SensitiveGuard.liftedByWhitelist` and [SecurityRule.whitelistable].
 */
internal fun ClaudeSettings.commandWhitelist(): List<String> =
    state.securityCommandWhitelist.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }

/**
 * The environment the guard resolves a `$VAR` against: **this IDE's own environment, with the settings' env block
 * on top** — which is exactly the order `ClaudeProcess` will hand to the binary (it inherits the parent
 * environment and the settings override it).
 *
 * This is what turns `cat $CREDS` from a string no rule matches into the path it names, judged by the rule that
 * fits. It is a READ of the environment and never a write, and no value from it is ever put into a card's reason —
 * the reason quotes the candidate as the model wrote it, so a variable's VALUE (which is where a token lives) does
 * not travel into the transcript.
 *
 * Deliberately NOT `resolveEnv()`: that sources the user's shell script, and this runs on every `can_use_tool`
 * request on the thread that reads the binary's entire stdout. A variable defined only by that script therefore
 * stays unresolved and ends in a card — and the script itself is read and judged by [EnvScriptLoader] before it is
 * ever sourced, which is the other half of the same answer.
 */
private fun launchEnvValues(settingsEnv: Map<String, String>): Map<String, String> =
    System.getenv() + settingsEnv

/**
 * Reads a script the guard is about to judge — bounded in size, and never anything but a regular file.
 *
 * The bound is the point: this runs on the reader thread, so an unbounded read of whatever path a model happened
 * to name is the same hazard as an unbounded `stat()`. A file over [MAX_ANALYSIS_BYTES] is not analysed and is
 * therefore treated as unreadable, which is the fail-closed direction — the guard then asks instead of assuming.
 */
private fun readForAnalysis(path: String): String? = runCatching {
    val file = java.io.File(path)
    if (!file.isFile || file.length() > MAX_ANALYSIS_BYTES) return@runCatching null
    file.readText()
}.getOrNull()

/**
 * How much of a script the guard is willing to read. 512 KiB is far past any shell script or build wrapper a
 * human wrote, and small enough that reading one on the reader thread is not a stall.
 */
private const val MAX_ANALYSIS_BYTES = 512L * 1024

/**
 * The proxy the session actually runs with, for [SensitiveGuard.Policy]'s egress data gate.
 *
 * Read from the settings' own env block first, then from **this IDE's** environment, because those are exactly the
 * two places the value the binary will use can come from: `ClaudeProcess` inherits the parent environment
 * (`withParentEnvironmentType(CONSOLE)`) and the settings' `envVars` are layered on top of it.
 *
 * Deliberately NOT through `resolveEnv()`, which SOURCES a shell script: this runs on every single `can_use_tool`
 * request, on the thread that reads the binary's entire stdout, and spawning a shell there would be the same
 * mistake as a synchronous `stat()` on that thread — only worse. A proxy declared exclusively inside a sourced
 * script is therefore invisible to this rule, which is a stated limitation and the correct trade: the rule then
 * simply never fires (a data gate, see [dev.lain.claudejb.permission.ProxyRules]), and nothing is over-blocked.
 *
 * Both spellings are checked, since `http_proxy` and `HTTP_PROXY` are equally conventional and a user who typed
 * the one this did not look for would silently get no rule at all.
 */
private fun Map<String, String>.proxyValue(lowerName: String): String? {
    val fromSettings = entries.firstOrNull { it.key.equals(lowerName, ignoreCase = true) }?.value
    val value = fromSettings?.takeIf { it.isNotBlank() }
        ?: System.getenv(lowerName)?.takeIf { it.isNotBlank() }
        ?: System.getenv(lowerName.uppercase())?.takeIf { it.isNotBlank() }
    return value?.trim()
}
