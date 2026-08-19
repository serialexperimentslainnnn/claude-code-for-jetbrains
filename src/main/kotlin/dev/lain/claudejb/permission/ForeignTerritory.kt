package dev.lain.claudejb.permission

object ForeignTerritory {

    private val HOME_SEGMENT = Regex("""(?:^|/)(?:home|users)/([^/]+)""", RegexOption.IGNORE_CASE)

    internal data class ForeignHit(val path: String, val rule: SecurityRule)

    internal fun foreignHit(paths: List<String>, policy: SensitiveGuard.Policy): ForeignHit? {
        val (ownRoots, guarded) = normalizedRoots(policy)
        return paths.asSequence()
            .filterNot { p -> ownRoots.any { GuardPaths.under(p, it) } }
            .mapNotNull { p -> foreignRuleFor(p, policy, guarded)?.let { ForeignHit(p, it) } }
            .firstOrNull()
    }

    private data class RootsKey(val projectRoot: String?, val home: String?, val guardedRoots: List<String>)

    private data class NormalizedRoots(val ownRoots: List<String>, val guarded: List<String>)

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

    private fun foreignRuleFor(
        path: String,
        policy: SensitiveGuard.Policy,
        guarded: List<String>,
    ): SecurityRule? = when {
        isUnc(path) -> SecurityRule.NETWORK_MOUNT
        foreignHome(path, policy.currentUser) -> SecurityRule.OTHER_USER_HOME
        policy.wslHost && underForeignMnt(path) -> SecurityRule.WSL_MOUNT
        guarded.any { GuardPaths.under(path, it) } -> SecurityRule.NETWORK_MOUNT
        else -> null
    }

    fun foreignHome(path: String, currentUser: String?): Boolean {
        if (path == "/root" || path.startsWith("/root/")) return !currentUser.equals("root", ignoreCase = true)
        val user = HOME_SEGMENT.find(path)?.groupValues?.get(1) ?: return false
        if (user.equals("shared", ignoreCase = true) || user.equals("public", ignoreCase = true)) return false
        return currentUser != null && !user.equals(currentUser, ignoreCase = true)
    }

    private fun underForeignMnt(path: String): Boolean =
        path.startsWith("/mnt/") && !path.startsWith("/mnt/c/") && path != "/mnt/c"

    /**
     * `\\server\share` / `//server/share` — remote by construction, on any OS: a doubled separator followed by
     * **a name a network could actually resolve** — a DNS/NetBIOS name, an IPv4 literal or an IPv6 literal.
     *
     * The shape requirement IS the rule. Without it the predicate was "a doubled separator plus anything with no
     * whitespace", which is satisfied by strings no network can reach: `//nolint:unused`, `//go:build`, and the
     * `//2` and `//2]` that Python integer division leaves behind once a command is tokenised on shell
     * separators. Every one of those was NETWORK_MOUNT — the hardest verdict in the set, DENY for every caller
     * with no override — on an ordinary source edit or a one-liner.
     */
    fun isUnc(path: String): Boolean {
        val p = path.replace('\\', '/')
        if (!p.startsWith("//") || p.length <= 2 || p[2] == '/') return false
        val rest = p.substring(2).let { if (it.startsWith("?/UNC/", ignoreCase = true)) it.substring(6) else it }
        return isNetworkHost(rest.substringBefore('/'))
    }

    private val DNS_LABEL = Regex("""[A-Za-z0-9_]([A-Za-z0-9_-]*[A-Za-z0-9_])?""")

    private fun isNetworkHost(host: String): Boolean = when {
        host.isEmpty() -> false
        host.startsWith("[") -> host.endsWith("]") && isIpv6(host.substring(1, host.length - 1))
        host.contains(':') -> isIpv6(host)
        else -> isIpv4(host) || isDnsName(host)
    }

    private fun isIpv4(host: String): Boolean {
        val parts = host.split('.')
        return parts.size == IPV4_OCTETS &&
            parts.all { it.length in 1..MAX_OCTET_DIGITS && it.all(Char::isDigit) && it.toInt() <= MAX_OCTET }
    }

    private fun isDnsName(host: String): Boolean {
        val name = host.removeSuffix("$").removeSuffix(".")
        if (name.isEmpty() || name.length > MAX_HOST_LEN) return false
        return name.split('.').all { it.length in 1..MAX_LABEL_LEN && DNS_LABEL.matches(it) }
    }

    private fun isIpv6(host: String): Boolean {
        val literal = host.substringBefore('%')
        if (literal.count { it == ':' } < 2) return false
        val halves = literal.split("::")
        if (halves.size > 2) return false
        val compressed = halves.size == 2
        val groups = halves.flatMap { half -> half.split(':').filter { it.isNotEmpty() } }
        val embedded = groups.lastOrNull()?.contains('.') == true
        if (embedded && !isIpv4(groups.last())) return false
        val hex = if (embedded) groups.dropLast(1) else groups
        val count = hex.size + if (embedded) 2 else 0
        if (if (compressed) count > IPV6_GROUPS - 1 else count != IPV6_GROUPS) return false
        return hex.all { group ->
            group.length in 1..MAX_GROUP_HEX && group.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        }
    }

    private const val MAX_HOST_LEN = 253
    private const val MAX_LABEL_LEN = 63
    private const val IPV6_GROUPS = 8
    private const val MAX_GROUP_HEX = 4
    private const val IPV4_OCTETS = 4
    private const val MAX_OCTET_DIGITS = 3
    private const val MAX_OCTET = 255
}
