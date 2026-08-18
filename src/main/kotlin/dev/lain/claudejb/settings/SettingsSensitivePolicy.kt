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
        hostResolver = ::resolvesAsHost,
        disabledRules = disabledSecurityRules(),
        httpProxy = env.proxyValue("http_proxy"),
        httpsProxy = env.proxyValue("https_proxy"),
        noProxyHosts = env.proxyValue("no_proxy").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
        extraBlockedDomains = extraBlockedDomains(),
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
 * stays unresolved and ends in a refusal — and the script itself is read and judged by [EnvScriptLoader] before it
 * is ever sourced, which is the other half of the same answer.
 */
private fun launchEnvValues(settingsEnv: Map<String, String>): Map<String, String> =
    System.getenv() + settingsEnv

/**
 * Reads a script the guard is about to judge — bounded in size, a regular file only, and **text only**.
 *
 * The bound is the point: this runs on the reader thread, so an unbounded read of whatever path a model happened
 * to name is the same hazard as an unbounded `stat()`. A file over [MAX_ANALYSIS_BYTES] is not analysed and is
 * therefore treated as unreadable, which is the fail-closed direction — the guard then refuses instead of assuming.
 */
private fun readForAnalysis(path: String): String? = runCatching {
    val file = java.io.File(path)
    if (!file.isFile || file.length() > MAX_ANALYSIS_BYTES) return@runCatching null
    val text = file.readText()
    // **A binary is NOT readable for this purpose, and saying otherwise was a hole big enough to run a
    // cryptominer through.** `readText()` on an ELF, a Mach-O or a PE returns mojibake — which matches no rule, so
    // `ScriptExecution` concluded "analysed, nothing found" and the launch was ALLOWED. The OPAQUE rules only fire
    // when a file cannot be read AT ALL, so a payload that had been downloaded and then executed (`./miner`) was
    // analysed to a clean verdict precisely BECAUSE there was nothing analysable in it. That is the two-step form of
    // `curl … | sh`, which IS caught: land the payload, then run it.
    //
    // A zero byte is the standard sniff for "this is not text" — the same one `git` uses to decide a file is binary
    // — and returning null puts the file back where it belongs: something the guard cannot judge, which is a refusal
    // naming the file rather than a pass. Compiled things are exactly what must not be run unexamined.
    //
    // Written as `it.code == 0` rather than as a character literal on purpose: a literal zero byte in a source file
    // is a zero byte in the repository, which is a binary file as far as git, diffs and review tooling are
    // concerned — this line went in that way once and had to be rewritten.
    if (text.any { it.code == 0 }) null else text
}.getOrNull()

/**
 * How much of a script the guard is willing to read. 512 KiB is far past any shell script or build wrapper a
 * human wrote, and small enough that reading one on the reader thread is not a stall.
 */
private const val MAX_ANALYSIS_BYTES = 512L * 1024

/**
 * Does this bare name resolve to an address on this network? — the one question
 * [SensitiveGuard.Policy.hostResolver] exists to answer, for the one candidate shape that format cannot settle:
 * `//<single-label>/<resource>`, which is `\\server\share` on a corporate LAN and `//noinspection` in a source
 * file. A name that resolves has a host behind it; a name that does not is a word.
 *
 * **Three properties, and all three are load-bearing.**
 *
 * *Bounded.* `InetAddress.getByName` has no timeout of its own and can sit on a dead resolver for the platform's
 * default (tens of seconds). This runs on the thread that reads the binary's entire stdout, so it is executed on
 * another thread with a deadline and the caller waits at most [HOST_LOOKUP_TIMEOUT_MS]. Same hazard, and the same
 * shape of answer, as the `stat()` bound in `GuardPaths.expandWithResolved`.
 *
 * *Fail-closed.* A lookup that cannot be answered in time returns **true** — the name counts as a host. The guard's
 * rule throughout is that what cannot be known is not waved through, and the alternative here is a bypass that
 * costs an attacker nothing but a slow DNS server.
 *
 * *Cached, but only the definitive answers.* A timeout is not cached, or one slow moment would mark a name a host
 * for the rest of the session (and the reverse, if the polarity were flipped, would be worse). The key space is
 * bare labels appearing in tool inputs, which is small and does not grow with traffic.
 */
private val hostLookupCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

private fun resolvesAsHost(name: String): Boolean {
    val key = name.lowercase()
    hostLookupCache[key]?.let { return it }
    val answer = runCatching {
        java.util.concurrent.CompletableFuture
            .supplyAsync { runCatching { java.net.InetAddress.getByName(key) != null }.getOrDefault(false) }
            .get(HOST_LOOKUP_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }.getOrNull()
    answer?.let { hostLookupCache[key] = it }
    return answer ?: true
}

/**
 * How long the guard will wait for a name lookup. Short on purpose: this is on the request path, a resolver that
 * answers at all answers in single-digit milliseconds from cache, and the fail-closed default means a miss costs
 * strictness rather than correctness.
 */
private const val HOST_LOOKUP_TIMEOUT_MS = 250L

// An `existsOnDisk` probe lived here, injected as `SensitiveGuard.Policy.pathExists`, so the outside-project rule
// could skip a destination with nothing existing on the way to it. Removed with that field: a path that is not there
// is a reason to refuse rather than to allow (a call naming somewhere absent is a mistake or a probe), and once that
// is the answer the probe changes no outcome — a destination outside the project is refused either way. It was a
// bounded syscall per candidate spent on a question nobody asked.

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
