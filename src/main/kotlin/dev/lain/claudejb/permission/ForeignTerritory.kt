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
 * A hit here denies **every caller regardless of trust**, by design — see [SensitiveGuard.verdict]. Each
 * sub-rule is tagged with its [SensitiveGuard.ForeignReason] so a single one can be softened to ASK from
 * Settings without touching the other two.
 */
object ForeignTerritory {

    /** Segment introducing a user home: `/home/<u>`, `/Users/<u>`, `C:/Users/<u>`, `/mnt/c/Users/<u>`. */
    private val HOME_SEGMENT = Regex("""(?:^|/)(?:home|users)/([^/]+)""", RegexOption.IGNORE_CASE)

    /** A FOREIGN-territory match, tagged with WHICH sub-rule tripped (see [SensitiveGuard.ForeignReason]) — the
     *  guard uses the tag to look up the right toggle, so each sub-rule can be softened to ASK independently of
     *  the others. */
    internal data class ForeignHit(val path: String, val reason: SensitiveGuard.ForeignReason)

    internal fun foreignHit(paths: List<String>, policy: SensitiveGuard.Policy): ForeignHit? {
        val ownRoots = listOfNotNull(
            policy.projectRoot?.let { GuardPaths.normalize(it, policy.home) },
            policy.home?.let { GuardPaths.normalize(it, null) },
        )
        val guarded = policy.guardedRoots.map { GuardPaths.normalize(it, policy.home) }.filter { it.isNotBlank() }
        return paths.asSequence()
            .filterNot { p -> ownRoots.any { GuardPaths.under(p, it) } } // our own territory is never foreign
            .mapNotNull { p -> foreignReasonFor(p, policy, guarded)?.let { ForeignHit(p, it) } }
            .firstOrNull()
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
     * **Real incident**: a `//`-prefixed value isn't necessarily UNC — an ordinary C/JS-style line comment
     * (`// see below`) starts with `//` too, and [GuardPaths.normalize] preserves that leading `//` (by design, so
     * a real UNC path survives normalization). The old check only asked "is the third character not another
     * slash", which a comment's leading space trivially satisfies — so `Edit`'s `old_string`/`new_string` (walked
     * as a path candidate like any other string leaf, see [ToolInputScanner.pathCandidates]) flagged FOREIGN and
     * hard-denied a completely ordinary edit, for the agent's own trusted tool, with no way to override it. A real
     * UNC hostname never contains whitespace; a comment almost always does right after the slashes — so require
     * the host segment (up to the next `/`) to be non-blank AND whitespace-free.
     */
    fun isUnc(path: String): Boolean {
        val p = path.replace('\\', '/')
        if (!p.startsWith("//") || p.length <= 2 || p[2] == '/') return false
        val host = p.substring(2).substringBefore('/')
        return host.isNotBlank() && host.none { it.isWhitespace() }
    }
}
