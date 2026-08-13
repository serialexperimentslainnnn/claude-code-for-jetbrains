package dev.lain.claudejb.permission

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The **path phase** of [SensitiveGuard]: one canonical textual form for every candidate, containment against a
 * root, and — when the IDE side supplies a resolver — the real on-disk path behind a candidate.
 *
 * This is a phase, not a rule family: [CredentialPaths], [ForeignTerritory] and [CommandRules] all judge what
 * this produces, so a change here moves every rule at once. The two bounds in [expandWithResolved] exist because
 * of a live incident, not for tidiness — read its doc before touching them.
 */
object GuardPaths {

    // ── normalisation ────────────────────────────────────────────────────────────────────────────────────

    /** One canonical form: `\`→`/`, env/`~` expanded, `//`→`/` (UNC's leading `//` kept), trailing `/` dropped. */
    fun normalize(path: String, home: String?): String {
        val expanded = expandEnv(path.trim(), home).replace('\\', '/')
        val unc = expanded.startsWith("//")
        val collapsed = expanded.replace(Regex("/{2,}"), "/")
        val result = if (unc) "/$collapsed" else collapsed
        return if (result.length > 1) result.trimEnd('/') else result
    }

    internal fun expandEnv(value: String, home: String?): String {
        var v = value
        if (!home.isNullOrBlank()) {
            val h = home.replace('\\', '/').trimEnd('/')
            v = v.replace("\${HOME}", h).replace("\$HOME", h)
                .replace("\$env:USERPROFILE", h, ignoreCase = true)
                .replace("%USERPROFILE%", h, ignoreCase = true)
                .replace("%HOMEPATH%", h, ignoreCase = true)
                .replace("%APPDATA%", "$h/AppData/Roaming", ignoreCase = true)
                .replace("%LOCALAPPDATA%", "$h/AppData/Local", ignoreCase = true)
            if (v == "~") {
                v = h
            } else if (v.startsWith("~/") || v.startsWith("~\\")) {
                v = h + "/" + v.substring(2)
            }
        }
        return v
    }

    /** Containment, on normalized forms: is [path] the root itself or something under it? */
    internal fun under(path: String, root: String): Boolean {
        val r = root.trimEnd('/')
        return r.isNotEmpty() && (path == r || path.startsWith("$r/", ignoreCase = true))
    }

    // ── resolution: the real path behind a candidate, bounded and off-thread ─────────────────────────────

    /**
     * Each candidate, plus — when a resolver is configured — its canonical real path. Deduped, order-stable.
     *
     * **This is the fix for a real incident**: [ToolInputScanner.pathCandidates] treats every bare word of a `Bash` command as a
     * candidate (`pathish` requires no path separator), so an ordinary command like `git commit -m 'fix: env
     * parsing'` used to produce a resolver call — a synchronous, unbounded, uninterruptible `stat()` — for EVERY
     * token (`git`, `commit`, `-m`, `fix:`, `env`, `parsing`…). That ran on the single thread that reads the
     * `claude` process's entire stdout stream, so a slow or hung filesystem (a stale network mount, WSL's 9p) froze
     * the whole transcript, not just one card — silently, since `runCatching` swallowed the eventual failure but
     * not the wait. Introduced alongside this class, caught only by a live report, never by a unit test (pure
     * functions don't model a hung syscall) — hence the two independent bounds below, not just one:
     *
     *  1. [looksResolvable] filters out candidates that cannot plausibly be a path AT ALL (most Bash tokens) before
     *     ever calling the resolver — this is what makes an ordinary command cheap again;
     *  2. even a resolvable candidate is only given [RESOLVE_TIMEOUT_MS] on a background thread ([resolveWithTimeout]) —
     *     this is what keeps a single `~/.ssh/id_rsa`-shaped argument, on a genuinely hung mount, from freezing
     *     anything: the reader thread moves on, at worst missing that one candidate's canonical form.
     * Capped at [MAX_RESOLVE_CANDIDATES] resolvable candidates as a third, coarser bound against a command crafted
     * with dozens of real-looking paths.
     */
    internal fun expandWithResolved(paths: List<String>, policy: SensitiveGuard.Policy): List<String> {
        val resolver = policy.pathResolver ?: return paths
        val out = LinkedHashSet<String>()
        var resolved = 0
        for (p in paths) {
            out += p
            if (resolved >= MAX_RESOLVE_CANDIDATES || !looksResolvable(p)) continue
            resolved++
            resolveWithTimeout(resolver, p)?.let { out += normalize(it, policy.home) }
        }
        return out.toList()
    }

    /**
     * Cheap, no-I/O pre-filter: could [token] plausibly BE a filesystem path? Most words in a shell command
     * (subcommands, flags, flag values, commit messages) cannot — they have no separator and no home/drive marker.
     * Requiring one before ever touching the resolver is what keeps ordinary `Bash` calls fast; a real path
     * (`~/.ssh/id_rsa`, `./script.sh`, `/etc/passwd`, `C:\Users\bob\x`) always has one.
     */
    private fun looksResolvable(token: String): Boolean =
        token.startsWith("~") || token.contains('/') || token.contains('\\')

    /**
     * Runs [resolver] on a background thread and waits at most [RESOLVE_TIMEOUT_MS] — never on the caller's thread.
     * On timeout, exception, or rejection, returns null (the literal candidate is still judged; only its resolved
     * form is missing). [future.cancel] cannot actually stop a blocked native `stat()` (Java has no safe way to do
     * that), so a genuinely hung call leaks one idle daemon thread in [resolverExecutor] rather than ever blocking
     * the process's stdout-reading thread — the trade this class exists to make.
     */
    private fun resolveWithTimeout(resolver: (String) -> String?, path: String): String? {
        val future = runCatching { resolverExecutor.submit(Callable { resolver(path) }) }.getOrNull() ?: return null
        return try {
            future.get(RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Cap on how many candidates per call get a (bounded, off-thread) resolve attempt — a coarse third bound. */
    private const val MAX_RESOLVE_CANDIDATES = 16

    /** How long a single resolver call may run before we give up on it and move on. */
    private const val RESOLVE_TIMEOUT_MS = 200L

    /** Daemon threads only — must never keep the JVM alive, and a stuck one (see [resolveWithTimeout]) is expected. */
    private val resolverExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "SensitiveGuard-resolver").apply { isDaemon = true }
    }
}
