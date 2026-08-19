package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

/**
 * [SecurityRule.UNRESOLVED_VARIABLE] and the variable half of [SecurityRule.RECURSION_LIMIT] — **a destination
 * written in terms of a variable**, judged after the guard has done everything it can to find out what that
 * variable says.
 *
 * ### The bypass, and why resolving beats refusing
 * Every rule in this package matches a STRING, and the guard's expansion vocabulary used to be `$HOME`, `~` and
 * the Windows profile variables. So:
 *
 * ```
 * cat $CREDS                       # value in the process environment
 * cat $XDG_CONFIG_HOME/gh/hosts.yml
 * A=$B; cat $A                     # …with B set somewhere else again
 * ```
 *
 * …reached **no rule at all**. Not a weaker match: none. `$CREDS` is not a credential glob, not another user's
 * home, not a temp directory — it is six characters that mean whatever the environment says.
 *
 * The fix is **analysis, not a blanket refusal**, and the difference matters in both directions.
 * [SensitiveGuard.Policy.envValues] carries the environment the session will actually be launched with, and
 * [GuardPaths.expandEnv] substitutes from it transitively, so `cat $CREDS` becomes the path it really names and is
 * refused as a **credential read**, with that wording, by that rule, at that severity. A rule that merely said
 * "this mentions a variable" would have been both weaker (it never tells you what the call was going to do) and
 * far noisier (`echo $PATH`, `--out $DIR`, every Makefile-shaped command line).
 *
 * ### What is left for this rule, which is the honest remainder
 *  - **Unresolved**: a name nothing in [SensitiveGuard.Policy.envValues] carries — set by a sourced script, or
 *    exported in an earlier turn of the same shell. The value is genuinely unknowable here, so the destination is
 *    unknowable, so it is not silent: a card ([SecurityRule.UNRESOLVED_VARIABLE]).
 *  - **Command substitution** (`$(…)`, backticks): a shell computing the argument. Same unknowability, one more
 *    step, and `CommandRules.deobfuscate` deliberately does not evaluate it — it peels laundering, it is not a
 *    shell.
 *  - **Deeper than the analysis follows, or cyclic**: [MAX_ANALYSIS_DEPTH] passes and the value is still moving.
 *    Reaching that bound is itself the finding — nothing legitimate needs a sixth hop to say where it is going —
 *    so it is [SecurityRule.RECURSION_LIMIT], a hard block for every caller rather than a card.
 *
 * ### Where it looks, and the one place it deliberately does not
 * [ToolInputScanner.destinationCandidates]: a location key, and every token of a command. **Not a payload key** —
 * a `$HOME` inside the text of a Makefile, a CI file or a shell script being WRITTEN is content, not a place the
 * call is going, and asking about it would turn every edit to any of those into a card while catching nothing.
 * That is the one question a payload has nothing to say about, and the scanner answers it so this rule cannot get
 * it wrong.
 */
object EnvIndirection {

    // There was a `SUBSTITUTION` blanket here that returned UNRESOLVED_VARIABLE for ANY `$(…)` or backtick, benign
    // or not. It is gone, and the reasoning is the whole design principle: a substitution is not refused for
    // BEING a substitution — `SensitiveGuard.substitutionFindings` recurses INTO the inner command and judges it
    // with the entire rule set, so `$(cat /etc/shadow)` is the credential read it is and `$(nmap …)` the hacking
    // tool it is. A benign inner command (`$(tty)`, `$(date)`, `$(git rev-parse HEAD)`) trips nothing and must
    // pass — blocking it was the exact "I can't expand this, so I refuse" reflex the guard's own doc says a rule
    // that refuses because it did not look is a rule that gets switched off. Expand and inspect, do not blanket.

    /** A variable reference left standing after expansion, in any spelling the guard understands. The dollar is
     *  `\x24` for the reason [GuardPaths]' own pattern spells it that way — see there; a literal one cannot be
     *  written in a raw string without either the compiler or ktlint objecting. */
    private val RESIDUAL_REF = Regex(
        """\x24\{[A-Za-z_][A-Za-z0-9_]*\}|\x24env:[A-Za-z_][A-Za-z0-9_]*|\x24[A-Za-z_][A-Za-z0-9_]*""" +
            """|%[A-Za-z_][A-Za-z0-9_]*%""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Names a command BINDS itself, so a reference to one is not a hidden destination — its value is either right
     * there in the command text (a `for`'s `in` list, an assignment's right-hand side, both of which the other
     * rules already judge) or supplied by the shell (a `read`). Flagging `$f` in `for f in a b c; do echo $f`
     * as "a destination hidden behind a variable this session can't resolve" is a false positive: `f` is not
     * hidden and not external, it is bound one clause earlier.
     *
     * **This loses no catch.** A loop over sensitive paths — a `for` whose `in` list is the `.ssh` directory with
     * a trailing glob (spelled with an ellipsis here, since slash-star opens a nested comment) — is caught at the
     * `in` list, because that `.ssh/…` glob is a path candidate the credential rule matches; the exemption is
     * only for the ITERATOR reference, whose concrete values were already on the command line and already judged.
     * Command substitution in the list — `for f in $(…)` — is deliberately NOT made resolvable by this: the list
     * itself is unknowable then, and stays a card.
     */
    private val FOR_VAR = Regex("""\bfor\s+(?:\(\(\s*)?([A-Za-z_][A-Za-z0-9_]*)\b""")
    private val READ_STMT = Regex("""\bread\b([^;&|\n]*)""")
    private val LOCAL_ASSIGN = Regex("""(?:^|[\s;&|(])([A-Za-z_][A-Za-z0-9_]*)=""")

    /** What a call's destinations turned out to be, once the guard had expanded everything it could. */
    internal class Verdict(val rule: SecurityRule, val text: String)

    /** Every name the command binds itself — [FOR_VAR] iterators, [READ_STMT] targets, [LOCAL_ASSIGN] left sides. */
    private fun locallyBoundNames(commands: List<String>): Set<String> {
        val out = HashSet<String>()
        for (command in commands) {
            FOR_VAR.findAll(command).forEach { out += it.groupValues[1] }
            LOCAL_ASSIGN.findAll(command).forEach { out += it.groupValues[1] }
            READ_STMT.findAll(command).forEach { m ->
                m.groupValues[1].trim().split(Regex("""\s+"""))
                    .filter { it.isNotEmpty() && !it.startsWith("-") }
                    .forEach { out += it }
            }
        }
        return out
    }

    /** The bare NAME inside a residual reference — brace form, env: form, bare-dollar form, or percent form. The
     *  last word-token is the name (so an `env:` prefix yields the variable, not the `env`). */
    private fun refName(ref: String): String? =
        Regex("""[A-Za-z_][A-Za-z0-9_]*""").findAll(ref).lastOrNull()?.value

    /**
     * The first destination whose value the guard could not pin down, with the rule that fits WHY — or null when
     * every destination resolved.
     *
     * The cap is checked before the residue, because the two are not mutually exclusive and the cap is the
     * stronger claim: a cyclic definition also leaves a reference standing, and reporting that as merely
     * "unresolved" would downgrade a hard block to a card.
     */
    internal fun indirectionHit(input: JsonObject, policy: SensitiveGuard.Policy): Verdict? {
        val bound = locallyBoundNames(ToolInputScanner.commandCandidates(input))
        for (raw in ToolInputScanner.destinationCandidates(input)) {
            if (raw.isBlank()) continue
            if (GuardPaths.exceedsEnvDepth(raw, policy.home, policy.envValues)) {
                return Verdict(SecurityRule.RECURSION_LIMIT, raw)
            }
            // No blanket refusal of command substitution — `SensitiveGuard.substitutionFindings` has already
            // recursed into the inner command and judged it, so a dangerous one was named as its real finding and
            // a benign one (`$(tty)`, `$(date)`) is allowed to pass. See the deleted `SUBSTITUTION` note above.
            val expanded = GuardPaths.expandEnv(raw, policy.home, policy.envValues)
            // A residual reference is a finding ONLY when the name is not bound by the command itself: a `for`
            // iterator, a `read` target or a local assignment is not a hidden external destination (see the
            // helpers above). All other residual names — set by a sourced script, an earlier turn, the
            // environment — remain genuinely unknowable, so they stay a card.
            val unresolvedExternal = RESIDUAL_REF.findAll(expanded)
                .mapNotNull { refName(it.value) }
                .any { it !in bound }
            if (unresolvedExternal) return Verdict(SecurityRule.UNRESOLVED_VARIABLE, raw)
        }
        return null
    }
}
