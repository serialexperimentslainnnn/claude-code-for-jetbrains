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
    fun normalize(path: String, home: String?, env: Map<String, String> = emptyMap()): String {
        val expanded = expandEnv(path.trim(), home, env)
        val unc = startsWithDoubleSeparator(expanded)
        val collapsed = expanded.replace('\\', '/').replace(MULTI_SEPARATOR, "/")
        val result = if (unc) "/$collapsed" else collapsed
        return if (result.length > 1) result.trimEnd('/') else result
    }

    /**
     * [normalize] runs on every path candidate, every glob (inside [CredentialPaths.compile]) and every home/
     * project root — a fresh `Regex("/{2,}")` per call was one avoidable compilation on the thread that reads
     * the binary's entire stdout. The pattern never varies, so one compiled instance serves every call.
     * Perf-only; revisit once phase 5's timings exist — if it bought nothing, revert it.
     */
    private val MULTI_SEPARATOR = Regex("/{2,}")

    /**
     * The UNC prefix, as written: two backslashes, or the forward-slash mirror every Unix-side tool accepts.
     * **The two characters must be the same one** — a mixed pair is a single separator next to an escape, not a
     * prefix, and reading it as one is what turned a regex literal into a network share (see [normalize]).
     */
    private fun startsWithDoubleSeparator(value: String): Boolean =
        value.length >= 2 && (value[0] == '\\' || value[0] == '/') && value[1] == value[0]

    /**
     * `~`, `$HOME` and the Windows profile variables from [home] — **and every other variable whose value [env]
     * carries**.
     *
     * [env] is the environment the session will actually be launched with (settings' own env block plus this
     * IDE's), and expanding from it is what turns `cat $CREDS` from an unjudgeable four-letter word into the path
     * it really names, judged by the rule that fits it. Without it the guard had two bad options and took the
     * worse one for years: match the literal `$CREDS` against a credential glob (it never matches) or refuse
     * every command that mentions a variable (which is most of them).
     *
     * A name [env] does not carry is left ALONE rather than blanked — the unexpanded spelling is still a candidate
     * (see `ToolInputScanner.bothSpellings`), and [EnvIndirection] is what notices that it stayed unresolved.
     * Blanking it would silently turn `$CREDS/id_rsa` into `/id_rsa`, i.e. invent a destination.
     */
    internal fun expandEnv(value: String, home: String?, env: Map<String, String> = emptyMap()): String {
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
        return if (env.isEmpty()) v else substituteEnv(v, env)
    }

    /**
     * Substitutes `$NAME`, `${NAME}`, `$env:NAME` and `%NAME%` from [env], **transitively**: a value that is
     * itself written in terms of another variable is expanded again, up to [MAX_ENV_PASSES].
     *
     * Transitive because one indirection is not a limit an attacker respects: `A=$B`, `B=~/.ssh/id_rsa` and
     * `cat $A` is the same bypass with one more hop, and stopping after a single pass would leave the guard
     * matching `$B`. The loop runs to a FIXPOINT and the cap is what makes it terminate — `A=$B` with `B=$A` is a
     * cycle, and it simply stops, leaving a residual reference behind. That residue is not a leak: it is exactly
     * what [EnvIndirection] reads as "this could not be resolved", so an unresolvable loop ends in a card.
     *
     * `Matcher.quoteReplacement` is not needed here because the replacement is built by hand rather than through
     * a template — but the hazard it guards against is the same one `CommandRules.substituteAssignments` was
     * fixed for, so a rewrite of this loop that reaches for `String.replace(Regex, String)` must not pass the
     * value straight in.
     */
    private fun substituteEnv(value: String, env: Map<String, String>): String = expandLoop(value, env).value

    /**
     * Whether [value] needed MORE than [MAX_ANALYSIS_DEPTH] passes to stop changing — i.e. a chain deeper than
     * the analysis follows, or a cycle.
     *
     * Reported separately from the expanded string because the two answers have different verdicts:
     * [SecurityRule.UNRESOLVED_VARIABLE] is a card for "nothing here could resolve this", and
     * [SecurityRule.RECURSION_LIMIT] is a hard block for "this was built so it could not be resolved". One loop
     * answers both, so they cannot disagree about what happened.
     */
    internal fun exceedsEnvDepth(value: String, home: String?, env: Map<String, String>): Boolean {
        if (env.isEmpty() && home.isNullOrBlank()) return false
        return expandLoop(expandEnv(value, home), env).exhausted
    }

    private class Expansion(val value: String, val exhausted: Boolean)

    /** Expand to a fixpoint, or give up at [MAX_ANALYSIS_DEPTH] and say so. */
    private fun expandLoop(value: String, env: Map<String, String>): Expansion {
        var v = value
        repeat(MAX_ANALYSIS_DEPTH) {
            val next = ENV_REF.replace(v) { m ->
                val name = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()
                lookup(env, name) ?: m.value // unknown: leave the reference standing, never blank it
            }
            if (next == v) return Expansion(v, exhausted = false)
            v = next
        }
        // Still moving after the last allowed pass: a deeper chain, or a cycle. Both are the finding.
        return Expansion(v, exhausted = ENV_REF.containsMatchIn(v))
    }

    /** Case-insensitive on the NAME, since `%Path%` and `$PATH` are the same variable on Windows. */
    private fun lookup(env: Map<String, String>, name: String): String? =
        env[name] ?: env.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /**
     * A variable reference in any of the four spellings the guard understands.
     *
     * **The dollar is spelled `\x24`, and it has to be spelled as something.** A literal one cannot appear in this
     * file at all — not in the pattern and not in this sentence. Backslash-dollar inside a raw string is a
     * backslash followed by a TEMPLATE, so the pattern does not compile (there is no variable called `env`); a
     * dollar in a character class does compile, but ktlint's own parser then reports `Identifier expected` and
     * refuses the file, prose included; and the escaped-template spelling reads as noise in the middle of a
     * pattern. `\x24` is the regex engine's own way to write the character, so every tool in the chain agrees.
     */
    private val ENV_REF = Regex(
        """\x24\{([A-Za-z_][A-Za-z0-9_]*)\}""" +
            """|\x24env:([A-Za-z_][A-Za-z0-9_]*)""" +
            """|\x24([A-Za-z_][A-Za-z0-9_]*)""" +
            """|%([A-Za-z_][A-Za-z0-9_]*)%""",
        RegexOption.IGNORE_CASE,
    )

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

    /**
     * Rooted at `/`, at a UNC host, or at a drive letter (`C:/…`) — **and carrying at least one letter or digit.**
     *
     * Internal because [SensitiveGuard]'s OUTSIDE_PROJECT rule needs it too: a bare relative token (most of an
     * ordinary command or edit) is not "outside the project" just because its literal spelling does not start
     * with the root's — only an absolute candidate can genuinely name a location elsewhere.
     *
     * **The alphanumeric requirement is what makes this a path test rather than a first-character test.** A
     * candidate of nothing but separators and punctuation — `/`, `//`, `///`, `//$` — names no file that could
     * exist, so it is not a location, and calling it one is how a `//` fragment of an expression ended up reported
     * as "outside the project": the right answer for a string that is not a path is *no rule*, not the weakest one.
     * Note the direction this fails in: it can only ever stop a rule firing on something that cannot name a file,
     * never on something that can.
     */
    internal fun isAbsolute(path: String): Boolean =
        (path.startsWith("/") || isDriveRooted(path)) && path.any { it.isLetterOrDigit() }

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
     * The first candidate that **acts outside** [projectRoot], or null when none does — the whole of
     * [SecurityRule.OUTSIDE_PROJECT]'s test, in the order the four conditions have to be asked in:
     *
     *  1. **rooted** — `/…` or `C:/…`; a relative candidate resolves under the working directory, which IS the root;
     *  2. **path-shaped** — carries a letter or a digit, so a run of separators and punctuation is not a location
     *     (both 1 and 2 are [isAbsolute]);
     *  3. **resolved** — the destination is asked of the filesystem, bounded and off-thread, and the literal is the
     *     fallback when the resolver cannot answer. NB `File.canonicalPath` resolves a path that does not exist, so
     *     this step is canonicalisation and not an existence test — those are two different questions and the next
     *     condition is the other one;
     *  4. **and it lands outside** — the containment test is applied to that destination, folded.
     *
     * **Existence is deliberately NOT a condition.** A destination that is not there is not a reason to allow the
     * call: it is a mistake or a probe, and a probe for a file outside the project is exactly the reconnaissance this
     * rule is for. It is also why nothing is gained by asking — a path outside the project is refused either way, so
     * an existence check would cost a syscall per candidate to compute an answer that changes no outcome. What it
     * WOULD change is the direction of the failure, from refusing something absent to permitting it.
     *
     * **Resolving BEFORE deciding is the difference between where a path is written and where it goes**, and it is
     * the reason this is a function rather than a filter at the call site. Folding `.`/`..` is textual: it cannot see
     * a symlink. `proj/link -> /etc` makes `proj/link/passwd` look like one of the project's own files to any string
     * comparison, and that was exactly what this rule was — so anything outside the project that no *stronger* rule
     * happens to name (a credential glob, another user's home, a device) was reachable through a link inside it.
     *
     * It cuts the other way too, and deliberately: a candidate whose literal is outside but which RESOLVES inside
     * the project — `/tmp/scratch/link -> proj/src` — is where the call actually acts, so it is not a hit here. That
     * is not a hole: whatever is objectionable about the path it travelled through belongs to the rule that owns it
     * ([TempDirs] for that example), and this rule's claim is only ever about the destination.
     */
    internal fun firstOutsideRoot(
        candidates: List<String>,
        projectRoot: String,
        policy: SensitiveGuard.Policy,
    ): String? {
        val root = fold(normalize(projectRoot, policy.home))
        // One budget for the whole call, spent across however many candidates there are — the same discipline as
        // [expandWithResolved]: a request carrying fifty paths must not cost fifty timeouts.
        val deadline = System.nanoTime() + RESOLVE_BUDGET_MS * NANOS_PER_MS
        return candidates.firstNotNullOfOrNull { outsideDestination(it, root, policy, deadline) }
    }

    /** Where [raw] actually lands, if that is outside [root] and real — else null. Conditions 1-5 of the doc above. */
    private fun outsideDestination(
        raw: String,
        root: String,
        policy: SensitiveGuard.Policy,
        deadline: Long,
    ): String? {
        if (!isAbsolute(raw)) return null
        val literal = fold(raw)
        val remainingMs = (deadline - System.nanoTime()) / NANOS_PER_MS
        val resolved = policy.pathResolver
            ?.takeIf { remainingMs > 0 }
            ?.let { resolveWithTimeout(it, literal, minOf(RESOLVE_TIMEOUT_MS, remainingMs)) }
            ?.let { fold(normalize(it, policy.home)) }
        val destination = resolved ?: literal
        return destination.takeUnless { under(it, root) }
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

    /**
     * One call's worth of concurrent resolves, never more. [resolveWithTimeout] already accepts that a hung
     * `stat()` leaks a thread — cancellation cannot reach it — but a cached pool means that leak is UNBOUNDED: a
     * mount that hangs every `stat()` (a dead NFS/SMB share, exactly the kind of path [ForeignTerritory] exists to
     * flag) spawns one more idle thread per call, forever, for as long as the session runs. A fixed pool caps the
     * worst case at [MAX_RESOLVER_THREADS] leaked threads total instead of one per call; a task that has to queue
     * behind a full pool is still bounded by [resolveWithTimeout]'s own `future.get` wait, so queueing costs time,
     * never correctness.
     */
    private const val MAX_RESOLVER_THREADS = 8

    /** Daemon threads only — must never keep the JVM alive, and a stuck one (see [resolveWithTimeout]) is expected. */
    private val resolverExecutor = Executors.newFixedThreadPool(MAX_RESOLVER_THREADS) { r ->
        Thread(r, "SensitiveGuard-resolver").apply { isDaemon = true }
    }
}
