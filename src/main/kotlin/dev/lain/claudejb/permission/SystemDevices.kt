package dev.lain.claudejb.permission

/**
 * [SecurityRule.SYSTEM_DEVICE] — **system devices**: any node under `/dev`, the Windows device namespace
 * (`\\.\…`), and another process's or the kernel's live memory. Addressing hardware directly, bypassing the
 * filesystem and every permission check it would normally apply.
 *
 * ### One pattern, not an enumeration
 * A path candidate that names `/dev/sda`, `/dev/nvidia0` or `/proc/1/mem` is not "a file the agent shouldn't
 * read" in the sense [CredentialPaths] means it — it is a request to talk to the machine underneath the
 * filesystem. Nothing about agentic development names a device, which is what makes the whole tree cheap to
 * hold: a hit is either a mistake with catastrophic blast radius (`dd` into the wrong block device), an attempt
 * to read past filesystem permissions (`/dev/mem`, `/proc/<pid>/mem`), or an attempt to reach hardware nobody
 * asked it to reach.
 *
 * This replaced a careful list of the dangerous nodes, and the replacement is the security change. **A list of
 * the bad ones is a blacklist, and a blacklist is what you miss the next item with**: the enumeration that was
 * here covered no GPU (`/dev/nvidia…`, `/dev/dri/…`, `/dev/kfd`), no virtualisation (`/dev/kvm`,
 * `/dev/vhost-net`), no bus (`/dev/bus/usb/…`, `/dev/i2c-…`), no capture device — and, worst of the set, not
 * `/dev/tcp/<host>/<port>`, which is bash opening a **network socket** spelled as a file and whose entire user
 * base is reverse shells. `^/dev(/|$)` covers all of them, and every node that ships next year.
 *
 * ### The two exemptions, and why an allowlist is the right shape for them
 * `/dev/null` and `/dev/urandom` are exempt, matched **exactly**. They are inert: no persistent state, and no
 * route through either to another process's or another user's data. The reason they need naming at all is that
 * `2>/dev/null` is not device access in any meaningful sense — it is punctuation, present in a large share of
 * ordinary commands — and a rule that refuses it is a rule the user switches off within an afternoon, taking
 * `/dev/tcp` and every disk and memory node with it. The exemption buys the rest of the tree.
 *
 * This is an **allowlist over a total pattern**, which is the opposite of the enumeration that was deleted: an
 * unknown node fails CLOSED, because it is unknown to a list of two rather than absent from a list of the bad
 * ones. Anything else under `/dev` — `/dev/zero`, `/dev/random`, `/dev/full`, `/dev/stdin`, `/dev/tty`,
 * `/dev/fd/<n>` — is still refused. Widen it only by adding a name here, deliberately, one at a time.
 *
 * **The accepted cost, stated rather than discovered:** output can be silenced. `2>/dev/null` hides a command's
 * failure from the transcript and from the reviewer, which is an obfuscation primitive as well as a shell idiom.
 * The trade is taken with that known, because a guard that interrupts routine work is a guard that gets disabled
 * wholesale, and the rules it takes down with it protect against far more than a hidden error message.
 */
object SystemDevices {

    /**
     * Exempt, and compared as a WHOLE path against the [GuardPaths.fold]ed, lower-cased candidate.
     *
     * Both halves of that are load-bearing. Whole-string equality (rather than a prefix or a `startsWith`) is
     * what stops `/dev/null.evil` or `/dev/urandom/../sda` from inheriting the exemption; folding first is what
     * makes `/dev/./null` inherit it, and what turns `/dev/null/../sda` into `/dev/sda` so it is judged as the
     * disk it actually names. Lower-casing covers `/DEV/NULL` on a case-insensitive filesystem — and an attacker
     * hoping the check is the other way round.
     */
    private val EXEMPT_DEVICES = setOf("/dev/null", "/dev/urandom")

    /**
     * Devices, matched against a [GuardPaths.normalize]d candidate. Each pattern is anchored so it names the
     * device node itself (or, for the directory-shaped ones, anything under it) — never a bare substring, for the
     * same reason `TempDirs.TEMP_ROOTS` is anchored: every string leaf of every tool input reaches this rule, so
     * an unanchored fragment would match ordinary paths that merely contain one of these words.
     */
    private val DEVICE_PATTERNS: List<Regex> = listOf(
        // Every device node, in one pattern. See the class doc for why this is not a list of the dangerous ones.
        Regex("""^/dev(/|$)""", RegexOption.IGNORE_CASE),
        // Windows' device namespace `\\.\…` — PhysicalDrive0, PIPE, COM1 — seen after GuardPaths.normalize turns
        // it into its forward-slash form. `ForeignTerritory.isUnc` deliberately does NOT match it (`.` is not a
        // hostname), so this is the rule that names it. NB `\\?\C:\…` is the extended-length LOCAL path prefix and
        // is not a device: it is not matched here, and must not be.
        Regex("""^//\./""", RegexOption.IGNORE_CASE),
        // /proc: another process's live memory, or the kernel's own. Not under /dev, so they need their own lines.
        Regex("""^/proc/\d+/mem$""", RegexOption.IGNORE_CASE),
        Regex("""^/proc/(kcore|kmem|kallsyms)$""", RegexOption.IGNORE_CASE),
    )

    /** The first candidate that names a system device, or null when none does. */
    internal fun deviceHit(paths: List<String>): String? = paths.firstOrNull { isSystemDevice(it) }

    /**
     * Is [path] a system device?
     *
     * Matched against both the literal and the [GuardPaths.fold]ed form, the same double check every other
     * location rule in this package applies (see [TempDirs.isTemp]) — but the EXEMPTION is decided on the folded
     * form alone, because that is the only form in which a spelling cannot be padded into looking inert.
     */
    fun isSystemDevice(path: String): Boolean {
        if (path.isBlank()) return false
        val folded = GuardPaths.fold(path)
        if (folded.lowercase() in EXEMPT_DEVICES) return false
        return matches(path) || matches(folded)
    }

    private fun matches(path: String): Boolean = DEVICE_PATTERNS.any { it.containsMatchIn(path) }
}
