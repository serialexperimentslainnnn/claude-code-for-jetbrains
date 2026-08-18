package dev.lain.claudejb.permission

/**
 * Rule family 2 of [SensitiveGuard] — **foreign territory**: another user's home (`/home/<not-me>`,
 * `/Users/<not-me>`, `C:\Users\<not-me>`, `/root`), a network/removable mount (NFS, CIFS/SMB, SSHFS, UNC
 * `\\server\share`), and — under **WSL** — anything on `/mnt/` that is not `/mnt/c`. None of that is agentic
 * development; it is lateral movement.
 *
 * Two exemptions, and both are about **a place, never about a threat** — the distinction [SensitiveGuard.classify]
 * states in full. The user's own home is not "another user's home", so exempting it is the definition of the rule.
 * The open project's root is exempt because a hit here is ENTIRELY its location: a repo on a corporate share is
 * normal, the user opened it on purpose, and without the exemption every single call in such a project is denied —
 * which does not block a threat, it blocks the work. (The case where that really is unacceptable is answered one
 * layer up and much earlier: [dev.lain.claudejb.session.RemoteMounts] refuses to launch a session at all on a
 * remote-mounted project.)
 *
 * **Neither exemption reaches the credential rule**, which judges what a file IS rather than where the project
 * sits, and is therefore enforced inside the project like everywhere else — see [SensitiveGuard.placeRules].
 *
 * Each of the three rules is returned by name, so a single one can be switched off from Settings (which turns it
 * into a card) without touching the other two.
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
            // Our own home and our own project are never foreign — a PLACE exemption, see the class doc. It does
            // not extend to the credential rule, which is judged over every candidate including these.
            //
            // **On the FOLDED spelling**, because the exemption is for the subtree and not for a prefix: `..` is
            // not collapsed by `GuardPaths.normalize`, so `<root>/../../home/someone-else/.ssh` starts with the
            // project root, is nowhere near it, and would otherwise be waved past this rule on the strength of
            // how it is written. See the matching note in `SensitiveGuard.weakRules`.
            .filterNot { p -> ownRoots.any { GuardPaths.under(GuardPaths.fold(p), it) } }
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
        isUnc(path, policy.hostResolver) -> SecurityRule.NETWORK_MOUNT

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

    /**
     * The host half of a network-resource path: an **IPv4 literal**, a bracketed **IPv6** literal, or a
     * **DNS/NetBIOS name** (one label, or dotted labels — RFC 1123 shape: alphanumeric ends, hyphens inside, ≤63
     * per label).
     *
     * A positive specification, and that distinction is the whole point of this rule's history. What sat here
     * before was the negative kind — "the segment after the `//` must not be blank and must not contain
     * whitespace" — i.e. *keep matching until the input looks reassuring*, which is a rule the input decides. This
     * asks the opposite question: does the candidate have the FORM of a network resource? A `// comment` does not,
     * not because it looks harmless but because ` comment` is not a hostname in any spelling.
     */
    private const val IPV4 = """\d{1,3}(?:\.\d{1,3}){3}"""
    private const val IPV6 = """\[[0-9A-Fa-f:.]{2,45}]"""

    /** One RFC 1123 label: alphanumeric ends, hyphens inside, at most 63 characters. */
    private const val LABEL = """[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"""

    /** A dotted name — two labels or more, so it is a name and not just a word. */
    private const val DOTTED = """$LABEL(?:\.$LABEL)+"""

    /** The resource half: a separator and then a share name that does not begin with another separator. */
    private const val RESOURCE = """/[^/\s]"""

    /**
     * **`//<ip | [ipv6] | dotted.name>/<resource>` — a network resource by FORM, validated whole.**
     *
     * One regex over the entire candidate rather than a hand-split host plus a couple of `if`s, because the whole
     * question is "is this string in the shape of a call to a network resource" and that is what a regex answers.
     * An IP literal or a dotted name cannot be anything else, so nothing further is asked about these.
     */
    private val UNC_QUALIFIED = Regex("""^//(?:$IPV4|$IPV6|$DOTTED)$RESOURCE""")

    /**
     * The one ambiguous shape: `//<single-label>/<resource>`, with the label captured so it can be RESOLVED.
     *
     * A bare label is a share host on a corporate network (`\\server\share`) and it is also what a source-code
     * comment looks like once the separators are normalised. Format cannot separate them; DNS can — see
     * [SensitiveGuard.Policy.hostResolver].
     */
    private val UNC_SHORT = Regex("""^//($LABEL)$RESOURCE""")

    /**
     * `\\server\share`, `\\10.0.0.5\c$`, `//fileserver/share` — remote by construction, on any OS.
     *
     * **Two things are required, and both come from what a network resource actually IS**: a doubled separator, a
     * host that matches [UNC_HOST], and a resource after it. A doubled separator alone is not a share — `\\?\C:\…`
     * is Windows' extended-length local path, `\\.\PhysicalDrive0` is its device namespace (caught, and named
     * correctly, by [SystemDevices]), and `// comment` is a line of source code.
     *
     * Written this way after two rounds of the wrong fix. Both earlier attempts narrowed the match by asking
     * whether the input looked innocent, which is exactly the judgement this guard must not make: the string comes
     * from the model, the model may be acting on an attacker's instructions, and "it seems fine" is not evidence.
     * Asking for the shape of a host instead has no such failure mode — it can be wrong about a real share whose
     * host is spelled unusually (widen [UNC_HOST], never loosen this), but it cannot be talked out of a match by a
     * candidate that was chosen to look ordinary.
     */
    fun isUnc(path: String, hostResolver: ((String) -> Boolean)? = null): Boolean {
        val p = path.replace('\\', '/')
        val body = when {
            // `\\?\UNC\server\share` is Win32's extended-length spelling of `\\server\share` — the same remote
            // path, and the prefix is what makes it look local. Handled explicitly, because `?` is not a host and
            // the host-shape test below would otherwise clear the one spelling chosen to get past it.
            p.startsWith("//?/UNC/", ignoreCase = true) -> p.substring("//?/UNC/".length)

            p.startsWith("//") -> p.substring(2)

            else -> return false
        }
        // Unambiguous by form: an IP literal or a dotted name with a resource after it.
        if (UNC_QUALIFIED.containsMatchIn(body.let { "//$it" })) return true
        // The ambiguous form. This is where a Python integer division leaves without a lookup ever being attempted:
        // `a // b` tokenises to a bare `//` — no host, no resource — and every spelling with whitespace after the
        // separator fails [LABEL]. Same for `// comment`. A label with a resource after it is asked of DNS: a name
        // that resolves is a host, and a host with a share after it is a network resource.
        val short = UNC_SHORT.find("//$body") ?: return false
        return hostResolver?.invoke(short.groupValues[1]) ?: true
    }
}
