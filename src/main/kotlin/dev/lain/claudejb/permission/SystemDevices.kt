package dev.lain.claudejb.permission

/**
 * [SecurityRule.SYSTEM_DEVICE] — **system devices**: any node under `/dev`, the Windows device namespace
 * (`\\.\…`), and another process's or the kernel's live memory. Addressing hardware directly, bypassing the
 * filesystem and every permission check it would normally apply.
 *
 * ### Why this is worth a rule of its own
 * A path candidate that names `/dev/sda`, `/dev/nvidia0` or `/proc/1/mem` is not "a file the agent shouldn't
 * read" in the sense [CredentialPaths] means it — it is a request to talk to the machine underneath the
 * filesystem. **Nothing about agentic development names a device at all**, which is what makes this rule cheap
 * to hold and total in scope: a hit is either a mistake with catastrophic blast radius (`dd` into the wrong
 * block device), an attempt to read past filesystem permissions (`/dev/mem`, `/proc/<pid>/mem`), an attempt to
 * reach hardware nobody asked it to reach (the GPU, the TPM, a USB bus, the microphone), or output being made to
 * disappear (`/dev/null`). None of those is a thing to confirm; the rule covers the whole tree.
 *
 * ### There is no benign exemption, and there never needed to be one
 * A `BENIGN_DEVICES` set used to sit here — `/dev/null`, `/dev/zero`, `/dev/random`, `/dev/urandom`, `/dev/full`,
 * the standard streams, `/dev/tty` — checked before the patterns, with a KDoc claiming that without it "every
 * redirect to `/dev/null` in every command would be a card". **That claim was false and the set was dead code**:
 * not one of those twelve names matches any entry in [DEVICE_PATTERNS], so the early return it performed could
 * never change an outcome. It was deleted rather than kept as reassurance, because an exemption that looks
 * load-bearing and is not is worse than none: it invites the next reader to widen it, and it hides the fact that
 * the patterns were already precise. **If a pseudo-device ever does need exempting, the honest fix is a narrower
 * pattern, not a list in front of it.**
 *
 * ### Terminal and pseudo devices ARE covered
 * Terminal devices (`/dev/tty…`, `/dev/pts/…` — spelled with an ellipsis rather than a star, since a slash
 * followed by a star OPENS a nested block comment in Kotlin and leaves this KDoc unclosed), the standard-stream
 * nodes, the descriptor directory and the randomness sources are all matched, each for a reason recorded at its
 * own pattern below. What is still NOT covered is a non-raw disk device NAME that only ever appears as a mount
 * source in ordinary tooling output: this rule is about a call that NAMES a device as its own target, not every
 * string that happens to look like one. Matching is by SHAPE, not by an exhaustive enumeration of every possible
 * node on every OS; widening it is a matter of adding a pattern, never of exempting one in front of them.
 */
object SystemDevices {

    /**
     * Raw devices, matched against a [GuardPaths.normalize]d candidate. Each pattern is anchored so it names
     * the device node itself (or, for the directory-shaped ones, anything under it) — never a bare substring,
     * for the same reason `TempDirs.TEMP_ROOTS` is anchored: every string leaf of every tool input reaches this
     * rule, so an unanchored fragment would match ordinary paths that merely contain one of these words.
     */
    private val DEVICE_PATTERNS: List<Regex> = listOf(
        // ── EVERY device node. One pattern, no enumeration, no exceptions. ───────────────────────────────
        //
        // This replaced a careful list of the dangerous ones — physical memory, block devices, mapper, loop,
        // macOS disks, input, framebuffer — plus a `BENIGN_DEVICES` allowlist in front of it. Both are gone, and
        // the reasoning is Lain's: **a model has no business reading, writing or otherwise naming a system device
        // at all.** Not a disk, not memory, not the randomness source, not a terminal, and not the GPU — a call
        // that opens `/dev/nvidia0` or `/dev/dri/renderD128` is not doing anything anyone asked for.
        //
        // Once that is the policy, enumerating the dangerous nodes is a **blacklist**, and a blacklist is exactly
        // what you miss the next node with. The list that was here covered no GPU (`/dev/nvidia…`, `/dev/dri/…`,
        // `/dev/kfd`), no virtualisation (`/dev/kvm`, `/dev/vhost-net`), no bus (`/dev/bus/usb/…`, `/dev/i2c-…`,
        // `/dev/spidev…`), no sound or capture (`/dev/snd/…`, `/dev/video…`), no `/dev/watchdog`, no `/dev/hidraw…`
        // — and, worst of the set, not `/dev/tcp/<host>/<port>`, which is bash opening a NETWORK SOCKET spelled as
        // a file. Every one of those would have had to be remembered. `^/dev/` remembers all of them and every one
        // that ships next year.
        //
        // The pseudo-devices are in scope for their own reasons, recorded because they are the ones that look
        // harmless: `/dev/null` (and `/dev/zero`/`/dev/full`) is the idiom for making output disappear — bad
        // practice on its own and an obfuscation primitive, since it hides from the transcript, the log and the
        // reviewer at once, and a rule set whose job is finding problems must not accept the token whose purpose is
        // that problems are not found; `/dev/urandom` is a device in exactly the sense `/dev/tpm` is, and "reading
        // it is harmless" is not the same claim as "a call has any business naming it"; `/dev/stdin` is an
        // injection surface in both directions, feeding the user's own shell or TTY as a source and writing files
        // by an unreviewed route as a target; `/dev/stdout`, `/dev/stderr` and `/dev/fd/<n>` are a command's own
        // output, which routinely carries sensitive data, redirected somewhere it is not expected; `/dev/tty…` and
        // `/dev/pts/…` are the user's terminal, i.e. their screen and their keystrokes.
        //
        // **If something here has to be allowed, it is allowed in Settings by the user**, by switching
        // [SecurityRule.SYSTEM_DEVICE] off — never by adding a name back in front of this pattern.
        Regex("""^/dev(/|$)""", RegexOption.IGNORE_CASE),
        // Windows' device namespace `\\.\…` — PhysicalDrive0, PIPE, COM1, and the rest — seen after
        // GuardPaths.normalize turns it into its forward-slash form. `ForeignTerritory.isUnc` deliberately does NOT
        // match it (`.` is not a hostname), so this is the rule that names it. NB `\\?\C:\…` is the extended-length
        // LOCAL path prefix and is not a device: it is not matched here, and must not be.
        Regex("""^//\./""", RegexOption.IGNORE_CASE),
        // /proc: another process's live memory, or the kernel's own.
        Regex("""^/proc/\d+/mem$""", RegexOption.IGNORE_CASE),
        Regex("""^/proc/(kcore|kmem|kallsyms)$""", RegexOption.IGNORE_CASE),
    )

    /** The first candidate that names a raw system device, or null when none does. */
    internal fun deviceHit(paths: List<String>): String? = paths.firstOrNull { isSystemDevice(it) }

    /** Is [path] a raw system device — matched against both the literal and the [GuardPaths.fold]ed form, the
     *  same double check every other location rule in this package applies (see [TempDirs.isTemp]). */
    fun isSystemDevice(path: String): Boolean {
        if (path.isBlank()) return false
        return matches(path) || matches(GuardPaths.fold(path))
    }

    private fun matches(path: String): Boolean = DEVICE_PATTERNS.any { it.containsMatchIn(path) }
}
