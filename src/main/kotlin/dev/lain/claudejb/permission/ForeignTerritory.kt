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
 * A hit here denies **every caller regardless of trust**, by design — see [SensitiveGuard.evaluate]. It is the
 * whole of [SecurityCategory.FOREIGN_TERRITORY], and each of its three rules is returned by name so a single one
 * can be softened to ASK from Settings without touching the other two.
 */
object ForeignTerritory {

    /** Segment introducing a user home: `/home/<u>`, `/Users/<u>`, `C:/Users/<u>`, `/mnt/c/Users/<u>` — matches
     *  anywhere in the candidate, not anchored to the start. */
    private val HOME_SEGMENT = Regex("""(?:^|/)(?:home|users)/([^/]+)""", RegexOption.IGNORE_CASE)

    /** A FOREIGN-territory match, tagged with WHICH rule tripped — the guard reads the tag to decide whether that
     *  one rule is enforced, so each can be softened to ASK independently of the other two. */
    internal data class ForeignHit(val path: String, val rule: SecurityRule)

    internal fun foreignHit(paths: List<String>, policy: SensitiveGuard.Policy): ForeignHit? {
        val (ownRoots, guarded) = normalizedRoots(policy)
        return paths.asSequence()
            .filterNot { p -> ownRoots.any { GuardPaths.under(p, it) } } // our own territory is never foreign
            .mapNotNull { p -> foreignRuleFor(p, policy, guarded)?.let { ForeignHit(p, it) } }
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
    private fun foreignRuleFor(
        path: String,
        policy: SensitiveGuard.Policy,
        guarded: List<String>,
    ): SecurityRule? = when {
        isUnc(path) -> SecurityRule.NETWORK_MOUNT

        foreignHome(path, policy.currentUser) -> SecurityRule.OTHER_USER_HOME

        // `wslHost` is the host fact, not the toggle: off WSL there is nothing to detect here, because `/mnt/data`
        // is then an ordinary directory. Whether a hit is enforced is `Policy.disabledRules`' business.
        policy.wslHost && underForeignMnt(path) -> SecurityRule.WSL_MOUNT

        guarded.any { GuardPaths.under(path, it) } -> SecurityRule.NETWORK_MOUNT

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

    /** `\\server\share` / `//server/share` — remote by construction, on any OS: a doubled separator whose first
     *  segment has no whitespace. */
    fun isUnc(path: String): Boolean {
        val p = path.replace('\\', '/')
        if (!p.startsWith("//") || p.length <= 2 || p[2] == '/') return false
        val host = p.substring(2).substringBefore('/')
        return host.isNotBlank() && host.none { it.isWhitespace() }
    }
}
