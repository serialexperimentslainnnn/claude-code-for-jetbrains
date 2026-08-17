package dev.lain.claudejb.permission

/**
 * Rule family 4 of [SensitiveGuard] — **the system temporary directory**: `/tmp` and the other spellings of
 * the same place.
 *
 * ### Why a temp directory is worth a rule of its own
 * It is the one directory on the machine that is world-writable, outside every review surface, and outside
 * the project — so it is where work goes when it is *not* meant to be seen. A script staged there and then
 * executed, an archive assembled there before it is uploaded, a copy of a file taken out of the project so
 * the next call can read it from somewhere the project's rules do not reach: none of those is a credential
 * path, none is a recognised dangerous command, and none leaves the machine, so rules 1–3 have nothing to
 * say about any of them. This rule says the only thing that can be said deterministically: an action there
 * is never silent.
 *
 * It is **not** a claim that `/tmp` is dangerous. It is a claim that an agent's use of it is worth one
 * glance, which is exactly what [SensitiveGuard.Verdict.ASK] costs a trusted caller.
 *
 * ### What counts, and what deliberately does not
 * Matching is **by segment, anchored at the start of the path** — `/tmp` itself and anything under it. A
 * segment boundary is the whole point: `/tmpfoo` is not `/tmp`, and `/home/u/tmp` is a directory the user
 * made inside their own home, not the system temp. A prefix test on the raw string would call both of them
 * hits, and a rule that refuses `~/tmp` is a rule that gets switched off.
 *
 * The spellings covered, all of them **structural** so they need no environment and no OS sniffing:
 *  - `/tmp` and `/var/tmp` — Linux, BSD, and every Unix;
 *  - `/private/tmp` and `/private/var/tmp` — macOS, where `/tmp` and `/var` are symlinks into `/private`, so
 *    both spellings reach the same directory and both have to be named;
 *  - `/var/folders/<xx>/<yyyy>/…` (and its `/private` spelling) — macOS' **per-user** temp, which is what
 *    `$TMPDIR` and therefore `mktemp` actually resolve to there. Without it the rule would have a hole on
 *    macOS wide enough to be the normal case rather than the exception, since almost nothing on that OS
 *    stages work in `/tmp` itself;
 *  - `C:\Windows\Temp` — Windows' machine-wide temp, natively and as WSL surfaces it (`/mnt/c/Windows/Temp`);
 *  - `…\Users\<u>\AppData\Local\Temp` — Windows' per-user temp, i.e. what `%TEMP%` and `%TMP%` normally
 *    expand to, matched by its structure rather than by reading the variable. (`%LOCALAPPDATA%\Temp\x` gets
 *    there on its own: [GuardPaths.expandEnv] already expands that variable against the user's home, and a
 *    Windows home IS `…/Users/<u>`.)
 *
 * **`TMPDIR`, `TEMP` and `TMP` are NOT read, and that is a limitation, not an oversight.** [SensitiveGuard]
 * is pure by contract — no IDE, no filesystem, no OS sniffing; everything it knows arrives on
 * [SensitiveGuard.Policy] — and the guard's only expansion vocabulary is [GuardPaths.expandEnv], which knows
 * `$HOME` and the Windows profile variables and nothing else. Reading the process environment from here
 * would put a hidden input into a rule whose entire testability rests on having none. So a candidate still
 * spelled `$TMPDIR/x` or `%TEMP%\x` is judged on its literal form and does not match; a candidate that
 * arrives already expanded (which is the normal case — a tool call names a real path) does. Giving the guard
 * a temp-directory seam on [SensitiveGuard.Policy] is the way to close that, and it is a deliberate
 * non-decision here rather than a seam invented in passing.
 *
 * ### Provenance — this rule sees exactly what the others see, and no more
 * It is matched against the candidate list `SensitiveGuard.classify` already built, so it inherits
 * [ToolInputScanner]'s provenance split for free and must never grow a lookup of its own: a **content** key
 * (`old_string`, `new_string`, `content`…) is payload and is not offered here at all, so editing a line of
 * documentation that mentions `/tmp/foo` is not an action on `/tmp` — while a **command** key is tokenised
 * and every token judged, because there the path genuinely lives inside the text. That asymmetry is the fix
 * for this package's recurring bug class (see `ToolInputScanner.CONTENT_KEY`); reproducing the mistake in a
 * new rule would reintroduce it.
 *
 * ### Folded before it is judged
 * `.` and `..` are the only way a path's spelling changes without the file it names changing, so
 * `/tmp/./././x` and `/tmp/../tmp/x` are `/tmp/x` and must trip the same rule. [GuardPaths.expandWithResolved]
 * already ADDS the folded form to the candidate set, but this rule folds again itself so it is correct on
 * whatever list it is handed rather than on an ordering elsewhere — and it judges the literal **as well as**
 * the folded form, never instead of it, because folding `..` is only sound when no segment on the way is a
 * symlink. Judging both can only add a match; judging only the folded form could lose one.
 *
 * ### Why this does not collide with the plugin's own use of `/tmp`
 * A backgrounded task's output really does live in the temp directory —
 * `/tmp/claude-<uid>/<cwd-encoded>/<sessionId>/tasks/<taskId>.output` — and the plugin reads it constantly
 * (`session/TaskOutputFile` finds the path, `session/LiveOutputTail` tails it, driven by `AgentScanner`'s
 * scan loop). That is not affected here: `LiveOutputTail.readNew` opens the file with `java.nio.file.Files`
 * from inside the plugin, so it never becomes a `can_use_tool` request and this guard is not on its path at
 * all. **The guard intercepts the binary, not the IDE** — the same reason `ui/GitIntegration.gitInit` is not
 * gated by it either.
 *
 * What the guard IS on the path of is the **agent** reading that same file, which the binary explicitly tells
 * it to do ("To check interim output, use Read on that file path"). That call is a `Read` — a trusted caller
 * — so it becomes a card, not a refusal. It is a real, recurring cost of the rule on any session that
 * backgrounds a task, and it is stated here rather than engineered away, because the alternative is a
 * path-shaped exemption, and an exemption that names a directory the agent can also WRITE to is a hole with
 * a name: anything the agent could be talked into staging under that same prefix would inherit it.
 *
 * ### The project root is exempt, like it is for rules 1 and 2
 * A project opened from under the temp directory — a scratch clone, a `git worktree` in `$TMPDIR`, an
 * extracted archive, the IDE's own test fixtures — is still the surface the user is looking at, and every
 * other location rule already says so. Without the exemption every single tool call in such a project is a
 * card, which is not a stricter guard but a disabled one: the user turns the rule off and loses the part
 * that mattered. The exemption is scoped by [GuardPaths.under] to the project SUBTREE, not to the temp
 * directory that contains it, so the rest of `/tmp` on that machine is guarded exactly as before.
 */
object TempDirs {

    /**
     * The temp directories, anchored at the start of a normalized path and terminated by a segment boundary.
     *
     * Each pattern ends in `/` and is matched against the path with a `/` appended ([isTemp]), which is how
     * "the directory itself or anything under it" is expressed without a `$` alternation in every one of
     * them — and, more importantly, how `/tmpfoo` is kept out: there is no boundary after `tmp` there.
     *
     * Anchored, in the same way and for the same reason as `ForeignTerritory.HOME_SEGMENT`: every string leaf
     * of every tool input reaches this rule, so an unanchored `temp/` segment would match ordinary source
     * trees, build outputs and Grep patterns.
     */
    private val TEMP_ROOTS: List<Regex> = listOf(
        // Unix: /tmp and /var/tmp, plus macOS' /private/tmp and /private/var/tmp — the same directories
        // reached through the symlinks macOS puts at the root, so both spellings have to be named.
        Regex("""^(?:[A-Za-z]:)?(?:/private)?(?:/var)?/tmp/""", RegexOption.IGNORE_CASE),
        // macOS' per-user temp: what $TMPDIR points at, and therefore where mktemp writes. Nothing but
        // temporary and cache material lives under this tree, so the whole root is the rule.
        Regex("""^(?:/private)?/var/folders/""", RegexOption.IGNORE_CASE),
        // Windows' machine-wide temp: C:\Windows\Temp natively, /mnt/c/Windows/Temp seen from WSL.
        Regex("""^(?:[A-Za-z]:)?(?:/mnt/[A-Za-z])?/windows/temp/""", RegexOption.IGNORE_CASE),
        // Windows' per-user temp — what %TEMP% / %TMP% expand to — recognised by structure, not by variable.
        Regex("""^(?:[A-Za-z]:)?(?:/mnt/[A-Za-z])?/users/[^/]+/appdata/local/temp/""", RegexOption.IGNORE_CASE),
    )

    /** The first candidate that names the temporary directory, or null when none does. */
    internal fun tempHit(paths: List<String>): String? = paths.firstOrNull { isTemp(it) }

    /**
     * Is [path] the system temporary directory, or something inside it?
     *
     * [path] is expected in [GuardPaths.normalize]d form (that is what the guard hands over): separators
     * forward, `~`/`$HOME` expanded, no trailing slash. The literal and its [GuardPaths.fold]ed form are both
     * tested — see the class doc for why both and not just the folded one.
     */
    fun isTemp(path: String): Boolean = matches(path) || matches(GuardPaths.fold(path))

    /** One spelling, tested with a trailing `/` so the directory itself matches its own descendants' pattern. */
    private fun matches(path: String): Boolean {
        if (path.isEmpty()) return false
        val probe = if (path.endsWith("/")) path else "$path/"
        return TEMP_ROOTS.any { it.containsMatchIn(probe) }
    }
}
