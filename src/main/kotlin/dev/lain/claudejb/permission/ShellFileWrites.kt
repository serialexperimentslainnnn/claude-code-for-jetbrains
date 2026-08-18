package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

/**
 * [SecurityRule.SHELL_FILE_WRITE] — **a command that writes or modifies a file**, as opposed to the reviewable
 * `Edit`/`Write`/`MultiEdit` tools.
 *
 * ### The gap this closes
 * `Bash` is not in `DiffPresenter.REVIEWABLE_TOOLS`: the plugin's whole diff-review story — the editable
 * "Current | Proposed" comparison shown before a write lands — exists only for the three file tools. A `Bash`
 * call that redirects output into a file, or invokes `cp`/`mv`/`sed -i`/`tee`/`rm`, mutates the filesystem with
 * **zero review surface**, and under `acceptEdits`/`bypassPermissions` it would otherwise run exactly as
 * silently as `ls`. This rule makes that call cost a card instead — the same bargain [TempDirs] and the other
 * location rules already strike, applied to a shell verb instead of a path.
 *
 * ### Why `sed`/`dd` need a flag and the rest do not
 * `cp`, `mv`, `rm`, `mkdir`, `touch`, `ln`, `chmod`, `chown`, `truncate`, `install`, `rsync`, `shred` and `tee`
 * have no ordinary invocation that does NOT mutate the filesystem — anchoring on the command name alone
 * ([CommandRules.cmdStart]) is precise, not merely convenient. `sed` and `dd` are different: `sed 's/a/b/' f`
 * prints to stdout and touches nothing, and `dd if=f` reads without writing anywhere — only `-i`/`--in-place`
 * on `sed` and `of=` on `dd` make either one a write. Matching them unconditionally would not be a stricter
 * rule, it would be a WRONG one: it flags calls this rule's own name says nothing about, and that kind of
 * over-reach is what gets a guard switched off (see [SensitiveGuard]'s class doc). Requiring the flag is
 * accurate detection, not a softened one.
 *
 * ### Redirection: real writes, not descriptor plumbing
 * `>`, `>>`, `>|`, and a numeric-fd form (`2>`, `1>>`) are real writes to whatever they name. `>&2` (duplicating
 * one descriptor onto another) is excluded — it moves a stream, not file content — and so is a redirect whose
 * target is one of the benign device sinks (`2>/dev/null`, the single most common redirect in ordinary shell
 * usage).
 *
 * ### What this rule knowingly over-matches, and why that is the accepted side
 * The command is matched **after [CommandRules.deobfuscate]**, which strips quotes, so a `>` inside a quoted
 * argument (`git commit -m "fix: a > b"`) reads as a redirect and raises a card. That is the deliberate direction
 * of the trade: this is the noisiest rule in the set — an agent runs `mkdir -p`, `touch`, `cp` and `rm`
 * constantly — and the alternative to over-matching is a shell parser, which is the thing this package's own
 * history says not to improvise. It costs a card for a trusted caller, the rule has its own switch, and both of
 * those are the answer to the noise; narrowing the pattern until it needs to understand quoting is not.
 *
 * Location-independent, like [CommandRules.DANGEROUS_COMMANDS]: a `tee` into a file is exactly as unreviewed
 * inside the project as outside it, so — unlike the credential/temp-dir rules — this one is not exempted by
 * the project root.
 */
object ShellFileWrites {

    /** Commands with no ordinary invocation that leaves the filesystem untouched — anchored at the shell
     *  position they actually run at, never a bare mention (a commit message that says "cp the config" is not
     *  a match; see [CommandRules.cmdStart]'s own doc for the incident this anchoring already fixed once). */
    private val BLANKET_MUTATORS =
        CommandRules.cmdStart("""tee|cp|mv|rsync|install|truncate|rm|mkdir|touch|ln|chmod|chown|shred""")

    /** `sed` only counts once it is actually asked to rewrite the file it reads. */
    private val SED_IN_PLACE = Regex(
        """(?:^|[;&|\n]\s*)(?:sudo\s+)?(?:\S*/)?sed\b[^;&|\n]*(-i\b|--in-place\b)""",
        RegexOption.IGNORE_CASE,
    )

    /** `dd` only counts once it names an output file. */
    private val DD_WRITE = Regex(
        """(?:^|[;&|\n]\s*)(?:sudo\s+)?(?:\S*/)?dd\b[^;&|\n]*\bof=\S""",
        RegexOption.IGNORE_CASE,
    )

    /** A real output redirect: `>`, `>>`, `>|`, optionally fd-numbered — but not `>&N` (descriptor duplication,
     *  no file involved). The captured group is the token right after it, judged by [isBenignTarget]. */
    private val REDIRECT = Regex("""\d*>{1,2}\|?(?!&)\s*(\S+)""")

    internal fun shellFileWrite(input: JsonObject): String? {
        for (raw in ToolInputScanner.commandCandidates(input)) {
            val command = CommandRules.deobfuscate(raw)
            BLANKET_MUTATORS.find(command)?.let { return it.value.trim() }
            SED_IN_PLACE.find(command)?.let { return it.value.trim() }
            DD_WRITE.find(command)?.let { return it.value.trim() }
            // `findAll(…).firstOrNull { … }` and not `find`: a redirect to `/dev/null` must not stop the scan of
            // the rest of the command, so the hit is the first redirect whose target is NOT benign, which is a
            // different question from "the first redirect".
            REDIRECT.findAll(command).firstOrNull { !isBenignTarget(it.groupValues[1]) }
                ?.let { return it.value.trim() }
        }
        return null
    }

    /**
     * A redirect target that writes nowhere: one of the inert device sinks, or a descriptor.
     *
     * **Matched against [BENIGN_REDIRECT_TARGETS] directly, and NOT through [SystemDevices.isSystemDevice].**
     * That was the original spelling and it was dead code with teeth: `isSystemDevice` returns **false** for these
     * nodes *by design*, since exempting them is its own job, so `isSystemDevice(t) && t in BENIGN` was a
     * conjunction that could never be true — every `2>/dev/null` in every command would have been a card, i.e.
     * nearly every ordinary command, which is precisely how a rule gets switched off in its first hour.
     */
    private fun isBenignTarget(rawTarget: String): Boolean {
        val target = rawTarget.trim('\'', '"').lowercase()
        if (target.startsWith("&")) return true // `> &2`-shaped, past the lookahead on a stray token break
        return BENIGN_REDIRECT_TARGETS.any { target == it || target.endsWith("/$it") }
    }

    /** The sinks with no persistent state, spelled without a leading separator so [isBenignTarget] can accept
     *  both `/dev/null` and a `/private`- or drive-prefixed spelling of the same node. */
    private val BENIGN_REDIRECT_TARGETS = setOf(
        "dev/null",
        "dev/zero",
        "dev/full",
        "dev/stdout",
        "dev/stderr",
        "dev/tty",
    )
}
