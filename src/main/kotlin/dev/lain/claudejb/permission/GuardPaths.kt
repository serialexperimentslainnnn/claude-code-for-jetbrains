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

    /**
     * One canonical form — and the UNC `//` prefix survives it only when the caller actually wrote a double
     * separator. The rest: `\`→`/`, env and `~` expanded, repeated separators collapsed, trailing `/` dropped.
     *
     * **That the prefix is read off the WRITTEN separators rather than the translated ones is the security
     * property, not a detail of ordering.** The flag used to be read off the string *after* `\` had become `/`, which
     * cannot tell `\\host\share` from a slash followed by a backslash — so any value beginning that way was
     * handed on wearing a UNC prefix it never had. A JavaScript or PCRE regex literal is exactly that shape (a
     * `/` opens it and an escape follows): the literal `\btype\s*:\s*` between its slash delimiters became
     * `//btype/s*:/s*`, whose first segment is hostname-shaped and whose second is non-empty, so
     * [ForeignTerritory.isUnc] read it as a network share. That is a `NETWORK_MOUNT` hit — DENY for every
     * caller, no trust exemption, no override — on a `Bash` call that only ran a search. Microsoft states the
     * prefix as a definition rather than a convention: a name is fully qualified when it begins with "A UNC name
     * of any format, which always start with two backslash characters" (*Naming Files, Paths, and Namespaces*,
     * `learn.microsoft.com/en-us/windows/win32/fileio/naming-a-file`). Slash-then-backslash is neither that nor
     * its accepted forward-slash mirror.
     *
     * **This can only ever withdraw a prefix that was manufactured here; it can never relax a real path.** The
     * flag changes for exactly two spellings — a slash followed by a backslash, and a backslash followed by a
     * slash — and neither names a file anywhere: the same page makes `\` a reserved character *inside* a name,
     * so `\btype` is not a Windows component, while on POSIX the string is a directory literally called
     * `\btype`. Nor is any candidate DROPPED, which is the direction that would be dangerous — a dropped
     * candidate is judged by no rule at all, so it is an ALLOW from every one of them at once. A value that
     * really is a path merely loses a prefix it never had and is then judged by the rule that fits it:
     * `/\home/bob/x` arrived as `//home/bob/x` and was refused as a network share — the right answer for the
     * wrong reason, and unreachable by the anchored `ForeignTerritory.foreignHome` — and now arrives as
     * `/home/bob/x`, which that rule refuses as what it is.
     *
     * Computed on the **env-expanded** form on purpose: a Windows home can itself be a UNC path, so `$HOME/x`
     * has to be able to acquire the prefix from the value substituted into it.
     */
    fun normalize(path: String, home: String?): String {
        val expanded = expandEnv(path.trim(), home)
        val unc = startsWithDoubleSeparator(expanded)
        val collapsed = expanded.replace('\\', '/').replace(Regex("/{2,}"), "/")
        val result = if (unc) "/$collapsed" else collapsed
        return if (result.length > 1) result.trimEnd('/') else result
    }

    /**
     * The UNC prefix, as written: two backslashes, or the forward-slash mirror every Unix-side tool accepts.
     * **The two characters must be the same one** — a mixed pair is a single separator next to an escape, not a
     * prefix, and reading it as one is what turned a regex literal into a network share (see [normalize]).
     */
    private fun startsWithDoubleSeparator(value: String): Boolean =
        value.length >= 2 && (value[0] == '\\' || value[0] == '/') && value[1] == value[0]

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

    /**
     * Containment, on normalized forms: is [path] the root itself or something under it?
     *
     * Case-insensitive on **both** halves, deliberately. It used to compare the root itself case-sensitively
     * (`path == r`) while comparing everything below it case-insensitively, so on Windows a root written in one
     * case did not contain itself while every file inside it did — the exact directory the user opened was the one
     * spelling of it that fell outside its own exemption. This class is pure and does no OS sniffing (see
     * [SensitiveGuard.Policy]), so it cannot ask the filesystem how it folds case; one rule for the whole
     * comparison is the only consistent answer, and the insensitive one is what already governed every path but
     * the root.
     */
    internal fun under(path: String, root: String): Boolean {
        val r = root.trimEnd('/')
        return r.isNotEmpty() && (path.equals(r, ignoreCase = true) || path.startsWith("$r/", ignoreCase = true))
    }

    // ── lexical resolution: `.`/`..` and the session's own working directory, with no disk involved ───────

    /**
     * The **lexical** real form of [path] — `.`/`..` folded, and a relative path anchored at [projectRoot] —
     * or null when that produces nothing new.
     *
     * [normalize] deliberately does not fold `.` or `..`: it is a *spelling* pass. Folding is a *meaning* pass and
     * belongs here, because two different laundering routes end at it and neither may depend on the filesystem
     * being reachable:
     *
     *  - **padding**: `~/.ssh` + `/.` repeated + `/id_rsa` is the same file, spelled long enough to slip past a
     *    length filter that judges the raw string. That filter is [ToolInputScanner]'s, and it folds through
     *    [fold] before it measures for exactly this reason — a cap applied to the raw spelling drops the
     *    candidate, and a dropped candidate is judged by no rule at all;
     *  - **traversal**: `proj/../../../etc/shadow` names a file nowhere near the project, and its literal spelling
     *    matches no glob that its folded spelling matches.
     *
     * And a **relative** candidate names something under the session's working directory, which is the project
     * root — the session is launched with `cwd` = project root. Anchoring it there is what lets
     * [ForeignTerritory.foreignHome] require an absolute spelling (its anchor is what stopped `./pages/home/Home`
     * being read as a stranger's home) without losing `../../<someone>/…`, which still resolves to a real foreign
     * path and is still denied.
     *
     * **The result is ADDED to the candidate set, never substituted for the literal.** Folding `..` is only sound
     * when no segment on the way is a symlink — `/home/me/proj/../x` is not `/home/me/x` if `proj` is a link — so
     * dropping the literal in favour of the folded form could *lose* a match. Judging both can only ever add one.
     */
    private fun lexicalForm(path: String, projectRoot: String?): String? {
        if (path.isEmpty() || path[0] in UNEXPANDED_PREFIXES) return null // still a variable
        val absolute = when {
            isAbsolute(path) -> path
            projectRoot.isNullOrBlank() -> return null
            else -> "$projectRoot/$path"
        }
        return fold(absolute).takeIf { it != path }
    }

    /** The first character of a candidate that no longer names a path but a variable still to be expanded. */
    private const val UNEXPANDED_PREFIXES = "~\$%"

    /** Rooted at `/`, at a UNC host, or at a drive letter — anything else is relative to the working directory. */
    private fun isAbsolute(path: String): Boolean = path.startsWith("/") || isDriveRooted(path)

    /** `C:/…` — the one absolute spelling that starts with neither `/` nor `//`. */
    private fun isDriveRooted(path: String): Boolean = path.length > 2 && path[1] == ':' && path[2] == '/'

    /** The rooted prefix a fold must preserve, since it is not a segment: `//host`'s `//`, a leading `/`, `C:/`. */
    private fun rootPrefix(path: String): String = when {
        path.startsWith("//") -> "//"
        path.startsWith("/") -> "/"
        isDriveRooted(path) -> path.substring(0, 3)
        else -> ""
    }

    /**
     * `a/./b` → `a/b`, `a/b/../c` → `a/c`. Purely textual: the root prefix (`/`, `//host`, `C:/`) is preserved,
     * and a `..` that would climb above a root is dropped rather than escaping it.
     *
     * Internal because [ToolInputScanner] needs it too: `.` and `..` segments are the one way a path's spelling
     * can grow without the file it names changing, so anything that judges a candidate BY ITS LENGTH has to fold
     * it first or the length is simply the attacker's to choose.
     */
    internal fun fold(path: String): String {
        val prefix = rootPrefix(path)
        val segments = ArrayList<String>()
        for (segment in path.substring(prefix.length).split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> climb(segments, prefix)
                else -> segments.add(segment)
            }
        }
        val body = segments.joinToString("/")
        return if (prefix.isEmpty()) body.ifEmpty { "." } else prefix + body
    }

    /**
     * One `..`: drop the segment above it. With nothing above it, what happens depends on whether anything roots
     * the path — a relative one keeps climbing (`../..` really is two levels up), while a rooted one stays put,
     * because `/..` is `/` and must never become an escape.
     */
    private fun climb(segments: MutableList<String>, prefix: String) {
        when {
            segments.isNotEmpty() && segments.last() != ".." -> segments.removeAt(segments.lastIndex)
            prefix.isEmpty() -> segments.add("..")
            else -> Unit
        }
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
     *     ever calling the resolver — this is what makes an ordinary command cheap again. It judges **the literal
     *     the caller wrote**, never a form this function derived from it: [lexicalForm] anchors a relative
     *     candidate at the project root, which manufactures a `/` in every bare word of a command, so filtering the
     *     derived form would let `git commit -m 'fix: env parsing'` back through as six resolvable paths — the
     *     incident itself, wearing the fix's own output as a disguise. Each literal therefore buys at most one
     *     resolve, of the one spelling the resolver can answer about ([anchored]);
     *  2. even a resolvable candidate is only given [RESOLVE_TIMEOUT_MS] on a background thread ([resolveWithTimeout]) —
     *     this is what keeps a single `~/.ssh/id_rsa`-shaped argument, on a genuinely hung mount, from freezing
     *     anything: the reader thread moves on, at worst missing that one candidate's canonical form.
     *  3. the whole call shares one [RESOLVE_BUDGET_MS] wall-clock budget, and the candidate set is a set, so each
     *     DISTINCT candidate costs at most one resolve.
     *
     * **Why a time budget and not a count.** The third bound used to be "at most 16 resolvable candidates", and a
     * count fails open at a number the attacker can simply exceed: sixteen resolvable decoys in front of the real
     * argument, and the seventeenth was judged on its literal spelling alone — which is precisely what resolution
     * exists to stop. A budget closes that by construction rather than by threshold: sixteen decoys buy nothing,
     * because on a healthy filesystem a `stat()` is microseconds and hundreds of them fit inside the budget, so
     * there is no number to beat. It is also strictly better at the other end — the count allowed 16 × 200 ms =
     * 3.2 s of the stdout-reading thread on a hung mount, worse than the incident that created it — which is why
     * this must not be turned back into a count, whatever number the count is given.
     *
     * **What stays open, and it is not hidden**: a genuinely slow (not hung) filesystem can exhaust the budget, and
     * the candidates after that point are judged on their literal spelling only — their symlink target is not seen.
     * That is not attacker-controllable (it needs the user's own disk to be sick), and the lexical half of the same
     * job — `.`/`..` folding and anchoring a relative path at the project root, see [lexicalForm] — is applied to
     * every candidate unconditionally and never touches the disk, so traversal laundering does not depend on any of
     * this. A symlink does, and only the filesystem knows about symlinks.
     */
    internal fun expandWithResolved(paths: List<String>, policy: SensitiveGuard.Policy): List<String> {
        val projectRoot = policy.projectRoot?.let { normalize(it, policy.home) }
        val out = LinkedHashSet<String>()
        val targets = LinkedHashSet<String>() // one per literal that looked like a path, deduped
        for (p in paths) {
            out += p
            lexicalForm(p, projectRoot)?.let { out += it }
            if (looksResolvable(p)) targets += anchored(p, projectRoot)
        }
        val resolver = policy.pathResolver ?: return out.toList()
        val deadline = System.nanoTime() + RESOLVE_BUDGET_MS * NANOS_PER_MS
        for (t in targets) {
            val remainingMs = (deadline - System.nanoTime()) / NANOS_PER_MS
            if (remainingMs <= 0) break
            resolveWithTimeout(resolver, t, minOf(RESOLVE_TIMEOUT_MS, remainingMs))
                ?.let { out += normalize(it, policy.home) }
        }
        return out.toList()
    }

    /**
     * The one spelling of [path] the resolver is asked about — the literal, except that a **relative** candidate is
     * anchored at [projectRoot] first.
     *
     * The resolver is `File(raw).canonicalPath` (see `SettingsSensitivePolicy`), which resolves a relative path
     * against the *IDE process's* working directory, not the session's — so asking it about `./gradlew` gets a
     * confident answer about a different file. The session's working directory is the project root, so anchoring is
     * what makes the question meaningful. An absolute literal is passed through unfolded on purpose: the filesystem
     * resolves `..` after symlinks and [fold] cannot, so the literal is the more accurate question of the two.
     * A candidate still carrying a variable ([UNEXPANDED_PREFIXES]) is passed through for the same reason in
     * reverse: anchoring it would invent a path nobody named.
     */
    private fun anchored(path: String, projectRoot: String?): String = when {
        isAbsolute(path) || projectRoot.isNullOrBlank() -> path
        path[0] in UNEXPANDED_PREFIXES -> path
        else -> "$projectRoot/$path"
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
     * Runs [resolver] on a background thread and waits at most [timeoutMs] — never on the caller's thread.
     * On timeout, exception, or rejection, returns null (the literal candidate is still judged; only its resolved
     * form is missing). [java.util.concurrent.Future.cancel] cannot actually stop a blocked native `stat()` (Java
     * has no safe way to do that), so a genuinely hung call leaks one idle daemon thread in [resolverExecutor]
     * rather than ever blocking the process's stdout-reading thread — the trade this class exists to make.
     */
    private fun resolveWithTimeout(resolver: (String) -> String?, path: String, timeoutMs: Long): String? {
        val future = runCatching { resolverExecutor.submit(Callable { resolver(path) }) }.getOrNull() ?: return null
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            null
        } catch (_: Exception) {
            null
        }
    }

    /** How long a single resolver call may run before we give up on it and move on. */
    private const val RESOLVE_TIMEOUT_MS = 200L

    /**
     * Wall-clock budget for **all** of one call's resolves together — the third bound, and the one that replaced a
     * count of candidates (see [expandWithResolved] for why a count could not stay). Sized so a hung filesystem
     * costs the stdout-reading thread less than the two [RESOLVE_TIMEOUT_MS] waits it takes to notice, while a
     * healthy one — where a `stat()` is microseconds — never comes close to it however many paths a command names.
     */
    private const val RESOLVE_BUDGET_MS = 500L

    private const val NANOS_PER_MS = 1_000_000L

    /** Daemon threads only — must never keep the JVM alive, and a stuck one (see [resolveWithTimeout]) is expected. */
    private val resolverExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "SensitiveGuard-resolver").apply { isDaemon = true }
    }
}
