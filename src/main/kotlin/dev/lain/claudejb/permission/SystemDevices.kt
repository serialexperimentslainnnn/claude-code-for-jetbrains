package dev.lain.claudejb.permission

/**
 * [SecurityRule.SYSTEM_DEVICE] — **raw system devices**: reading or writing the block device, physical
 * memory, or kernel-memory node directly, bypassing the filesystem and every permission check it would
 * normally apply.
 *
 * ### Why this is worth a rule of its own
 * A path candidate that names `/dev/sda` or `/proc/1/mem` is not "a file the agent shouldn't read" in the
 * sense [CredentialPaths] means it — it is a request to read or write the device that BACKS the filesystem,
 * or another process's live memory. Nothing about ordinary agentic development touches these nodes; a hit
 * here is either a mistake with catastrophic blast radius (`dd` into the wrong block device) or a deliberate
 * attempt to read past filesystem permissions (`/dev/mem`, `/proc/<pid>/mem`) or to fingerprint/exfiltrate raw
 * disk contents a file-level scan would never surface.
 *
 * ### The benign exemption, and why it has to exist
 * `/dev/null`, `/dev/zero`, `/dev/random`/`/dev/urandom`, `/dev/full`, and the three standard-stream nodes are
 * pseudo-devices with no persistent state and no way to read another process's or another user's data through
 * them — `2>/dev/null` and `< /dev/urandom` are ordinary shell idioms, not device access. Without this
 * allowlist every redirect to `/dev/null` in every command would be a card, which is exactly the "cries wolf"
 * failure [SensitiveGuard]'s own class doc warns about.
 *
 * ### What deliberately is NOT covered
 * Terminal devices (`/dev/tty…`, `/dev/pts/…` — spelled with an ellipsis rather than a star, since a slash
 * followed by a star OPENS a nested block comment in Kotlin and leaves this KDoc unclosed) and non-raw disk
 * device NAMES that only ever appear as a mount
 * source in ordinary tooling output are left alone — this rule is about a call that NAMES the device as its
 * own target, not every string that happens to look like one. Matching is by SHAPE, not by an exhaustive
 * enumeration of every possible device node on every OS; widening it is a matter of adding a pattern, never of
 * trusting a caller.
 */
object SystemDevices {

    /**
     * Provably inert: no persistent state, and no route through them to another user's or process's data.
     * Compared against the lower-cased candidate so `/DEV/NULL` (case-insensitive filesystems, or an attacker
     * hoping the check is case-sensitive) is exempted exactly like the canonical spelling.
     */
    private val BENIGN_DEVICES = setOf(
        "/dev/null", "/dev/zero", "/dev/full", "/dev/random", "/dev/urandom",
        "/dev/stdin", "/dev/stdout", "/dev/stderr", "/dev/fd/0", "/dev/fd/1", "/dev/fd/2", "/dev/tty",
    )

    /**
     * Raw devices, matched against a [GuardPaths.normalize]d candidate. Each pattern is anchored so it names
     * the device node itself (or, for the directory-shaped ones, anything under it) — never a bare substring,
     * for the same reason `TempDirs.TEMP_ROOTS` is anchored: every string leaf of every tool input reaches this
     * rule, so an unanchored fragment would match ordinary paths that merely contain one of these words.
     */
    private val DEVICE_PATTERNS: List<Regex> = listOf(
        // Direct physical/kernel memory, raw I/O ports, the kernel ring buffer — no partition scheme applies,
        // so these are exact nodes, not prefixes.
        Regex("""^/dev/(mem|kmem|port|kmsg)$""", RegexOption.IGNORE_CASE),
        // Raw and partitioned block devices: the disk itself, not a filesystem mounted from it.
        Regex(
            """^/dev/(sd[a-z]+\d*|nvme\d+n\d+(p\d+)?|hd[a-z]+\d*|xvd[a-z]+\d*|vd[a-z]+\d*|mmcblk\d+(p\d+)?)$""",
            RegexOption.IGNORE_CASE,
        ),
        Regex("""^/dev/mapper/""", RegexOption.IGNORE_CASE),
        Regex("""^/dev/(loop|dm-)\d+$""", RegexOption.IGNORE_CASE),
        // macOS: /dev/disk0, /dev/disk0s1, /dev/rdisk0 (the raw/character variant).
        Regex("""^/dev/r?disk\d""", RegexOption.IGNORE_CASE),
        Regex("""^/dev/input/""", RegexOption.IGNORE_CASE),
        Regex("""^/dev/fb\d+$""", RegexOption.IGNORE_CASE),
        // /proc: another process's live memory, or the kernel's own.
        Regex("""^/proc/\d+/mem$""", RegexOption.IGNORE_CASE),
        Regex("""^/proc/(kcore|kmem|kallsyms)$""", RegexOption.IGNORE_CASE),
        // Windows' raw device namespace, seen after GuardPaths.normalize turns `\\.\PhysicalDrive0` into its
        // forward-slash form. (ForeignTerritory.isUnc already flags the same string as a UNC-shaped path first —
        // see SensitiveGuard.classify's ordering — so this pattern is what names it correctly if that ever stops
        // being true, not the only thing standing in front of it today.)
        Regex("""^//\./physicaldrive\d+""", RegexOption.IGNORE_CASE),
    )

    /** The first candidate that names a raw system device, or null when none does. */
    internal fun deviceHit(paths: List<String>): String? = paths.firstOrNull { isSystemDevice(it) }

    /** Is [path] a raw system device — matched against both the literal and the [GuardPaths.fold]ed form, the
     *  same double check every other location rule in this package applies (see [TempDirs.isTemp]). */
    fun isSystemDevice(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.lowercase() in BENIGN_DEVICES) return false
        return matches(path) || matches(GuardPaths.fold(path))
    }

    private fun matches(path: String): Boolean = DEVICE_PATTERNS.any { it.containsMatchIn(path) }
}
