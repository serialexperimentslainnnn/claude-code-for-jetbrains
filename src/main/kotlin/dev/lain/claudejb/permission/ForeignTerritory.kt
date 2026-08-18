package dev.lain.claudejb.permission

/**
 * Rule family 2 of [SensitiveGuard] — **foreign territory**: another user's home (`/home/<not-me>`,
 * `/Users/<not-me>`, `C:\Users\<not-me>`, `/root`), a network/removable mount (NFS, CIFS/SMB, SSHFS, UNC
 * `\\server\share`), and — under **WSL** — anything on `/mnt/` that is not `/mnt/c`. None of that is agentic
 * development; it is lateral movement.
 *
 * The only exemption is the open project's own root (and the user's own home): a repo on a corporate share is
 * normal and the user opened it on purpose. The credential globs still apply to it.
 *
 * A hit here denies **every caller regardless of trust**, by design — see [SensitiveGuard.evaluate]. Each
 * sub-rule is tagged with its [SensitiveGuard.ForeignReason] so a single one can be softened to ASK from
 * Settings without touching the other two.
 */
object ForeignTerritory {

    /**
     * Segment introducing a user home: `/home/<u>`, `/Users/<u>`, `C:/Users/<u>`, `/mnt/c/Users/<u>`, plus the
     * platforms that put the same directory one level down (`/var/home` on image-based Fedora, `/export/home` on
     * Solaris, `/usr/home` on FreeBSD).
     *
     * **Anchored to the start of the path, and that anchor is the whole rule.** It used to be `(?:^|/)`, which
     * matches a `home/` or `users/` segment ANYWHERE in ANY string — and [ToolInputScanner.pathCandidates] hands
     * this every string leaf that could be a location, plus every token of every command. A React route
     * (`./pages/home/Home`), a `src/users/` directory, a `Grep` pattern: all of them yielded a "user" and, since
     * that user is not you, a FOREIGN hit — which denies **every** caller regardless of trust, with no override,
     * and tells the user in the denial where the switch to turn the rule off is. Confirmed live against an agent
     * that was only reading code: a `python3` heredoc containing `import Home from './pages/home/Home';` was
     * refused outright.
     *
     * The anchor is one of two independent answers to that family and does not subsume the other: a payload that
     * IS a path (`old_string` = `/home/bob/.cache`) satisfies any anchor, and is kept out by never being offered
     * here at all (`ToolInputScanner.CONTENT_KEY`). A recogniser cannot tell a quote from a destination.
     *
     * A user home is an absolute location, so only an absolute spelling can name one. A RELATIVE candidate names
     * something under the session's own working directory, and [GuardPaths.expandWithResolved] is what turns it
     * into an absolute form (anchored at the project root) before it reaches here — so an escape written as
     * `../../<someone>/…` is still caught, on that added form, without a bare `home/` segment ever meaning
     * anything on its own.
     */
    private val HOME_SEGMENT = Regex(
        """^(?:[A-Za-z]:)?(?:/mnt/[A-Za-z]|/var|/export|/usr)?/(?:home|users)/([^/]+)""",
        RegexOption.IGNORE_CASE,
    )

    /** A FOREIGN-territory match, tagged with WHICH sub-rule tripped (see [SensitiveGuard.ForeignReason]) — the
     *  guard uses the tag to look up the right toggle, so each sub-rule can be softened to ASK independently of
     *  the others. */
    internal data class ForeignHit(val path: String, val reason: SensitiveGuard.ForeignReason)

    internal fun foreignHit(paths: List<String>, policy: SensitiveGuard.Policy): ForeignHit? {
        val (ownRoots, guarded) = normalizedRoots(policy)
        return paths.asSequence()
            .filterNot { p -> ownRoots.any { GuardPaths.under(p, it) } } // our own territory is never foreign
            .mapNotNull { p -> foreignReasonFor(p, policy, guarded)?.let { ForeignHit(p, it) } }
            .firstOrNull()
    }

    private data class RootsKey(val projectRoot: String?, val home: String?, val guardedRoots: List<String>)

    private data class NormalizedRoots(val ownRoots: List<String>, val guarded: List<String>)

    /**
     * `ownRoots`/`guarded` are pure functions of [policy]'s roots, and `SettingsSensitivePolicy` builds a fresh
     * `Policy` on every `can_use_tool` while the project root, home and guarded-mount set change on the order of
     * once a session — every call was re-normalising the same handful of strings. Keyed on the three inputs that
     * actually vary, not on `policy` itself (a fresh data-class instance every call would never hit the cache).
     * Unbounded on purpose: the key space is one project root, one home and one mount list per running IDE, not
     * something that grows with traffic. Perf-only; revisit once phase 5's timings exist — if it bought nothing,
     * revert it.
     */
    private val rootsCache = java.util.concurrent.ConcurrentHashMap<RootsKey, NormalizedRoots>()

    private fun normalizedRoots(policy: SensitiveGuard.Policy): NormalizedRoots =
        rootsCache.computeIfAbsent(RootsKey(policy.projectRoot, policy.home, policy.guardedRoots)) {
            NormalizedRoots(
                ownRoots = listOfNotNull(
                    policy.projectRoot?.let { GuardPaths.normalize(it, policy.home) },
                    policy.home?.let { GuardPaths.normalize(it, null) },
                ),
                guarded = policy.guardedRoots.map { GuardPaths.normalize(it, policy.home) }
                    .filter { it.isNotBlank() },
            )
        }

    /** Why [path] counts as foreign territory, or null when it does not. First rule that matches wins. */
    private fun foreignReasonFor(
        path: String,
        policy: SensitiveGuard.Policy,
        guarded: List<String>,
    ): SensitiveGuard.ForeignReason? = when {
        isUnc(path) -> SensitiveGuard.ForeignReason.NETWORK_MOUNT
        foreignHome(path, policy.currentUser) -> SensitiveGuard.ForeignReason.OTHER_USER_HOME
        policy.blockForeignWslMounts && underForeignMnt(path) -> SensitiveGuard.ForeignReason.WSL_MOUNT
        guarded.any { GuardPaths.under(path, it) } -> SensitiveGuard.ForeignReason.NETWORK_MOUNT
        else -> null
    }

    /** Another user's home (`/home/<other>`, `/Users/<other>`, `C:/Users/<other>`, `/root` unless we are root). */
    fun foreignHome(path: String, currentUser: String?): Boolean {
        if (path == "/root" || path.startsWith("/root/")) return !currentUser.equals("root", ignoreCase = true)
        val user = HOME_SEGMENT.find(path)?.groupValues?.get(1) ?: return false
        if (user.equals("shared", ignoreCase = true) || user.equals("public", ignoreCase = true)) return false
        return currentUser != null && !user.equals(currentUser, ignoreCase = true)
    }

    /** WSL: `/mnt/<x>` where x ≠ c — a foreign or network Windows drive surfaced under the Linux root. */
    private fun underForeignMnt(path: String): Boolean =
        path.startsWith("/mnt/") && !path.startsWith("/mnt/c/") && path != "/mnt/c"

    /**
     * `\\server\share` / `//server/share` — remote by construction, on any OS.
     *
     * A UNC path names a **host and a share**, and its host is a hostname: letters, digits, `.`, `-` and `_`,
     * nothing else. Both halves of that are load-bearing here, because a `//` prefix on its own is common in
     * things that are entirely local — and [ToolInputScanner.pathCandidates] hands every one of them to this
     * rule, since it walks every string leaf of a tool's input and every token of a command:
     *
     *  - a **line comment**: `// see below`, in any leaf that is a location. [GuardPaths.normalize] keeps that
     *    leading `//` (by design, so a real UNC path survives normalization), and a hostname has no whitespace.
     *    An `Edit`'s `old_string` was the reported case and no longer reaches here at all
     *    (`ToolInputScanner.CONTENT_KEY`), but a comment is not confined to one; the check stays.
     *  - a **fragment of code**: a command is tokenised on shell separators, so integer division right after one
     *    of them leaves `//` glued to its operand — `len(xs)//2` → `//2`, `xs[len(xs)//2]` → `//2]`,
     *    `sum(v)//len(v)` → `//len`. `]` is not a hostname character, and a first segment with nothing after it
     *    names no share.
     *
     * Getting this wrong is not a near miss: FOREIGN denies **every** caller regardless of trust and has no
     * override, so a misread comment or expression refuses an ordinary edit or an ordinary command outright.
     *
     * **A third route into the same mistake is closed upstream, in [GuardPaths.normalize], and cannot be closed
     * here.** A regex literal opens with a separator and an escape, so translating `\` to `/` manufactured a
     * `//` prefix out of a value that never carried one; the prefix is now taken from the spelling the caller
     * wrote. What that leaves behind is one spelling this rule genuinely cannot judge: a regex whose escapes are
     * themselves doubled for a shell, `\\btype\\s*` and its relatives, is character-for-character a UNC host and
     * share, and the raw form of a command must keep reaching this rule because that is the only form a
     * `cmd.exe` or PowerShell UNC argument survives in (a POSIX shell's is covered by
     * [CommandRules.deobfuscate]). **Do not close it with a legal-name test on the share.** Microsoft's reserved
     * set (*Naming Files, Paths, and Namespaces*) makes the two characters that would discriminate `*` and `:`,
     * and both are load-bearing elsewhere: `*` is an ordinary glob against a real share, and `:` opens an NTFS
     * alternate data stream — so refusing a component that contains either would relax a genuine DENY and hand
     * back a known Windows path-filter evasion, for a false positive one spelling wide.
     *
     * The Win32 device / extended-length namespace (`\\?\UNC\server\share`, `\\?\C:\dir`, `\\.\pipe\x`) has no
     * hostname to check, and is kept foreign as a whole: `\\?\UNC\…` IS remote, and raw devices are not agentic
     * development either.
     */
    fun isUnc(path: String): Boolean {
        val p = path.replace('\\', '/')
        if (!p.startsWith("//") || p.length <= 2 || p[2] == '/') return false
        val segments = p.substring(2).split('/')
        if (segments[0] == "?" || segments[0] == ".") return true
        return UNC_HOST.matches(segments[0]) && segments.getOrElse(1) { "" }.isNotBlank()
    }

    /** A UNC host: a hostname or an IP literal — never a shell token, a comment, or the tail of an expression. */
    private val UNC_HOST = Regex("""[A-Za-z0-9](?:[A-Za-z0-9._\-]*[A-Za-z0-9])?""")
}
