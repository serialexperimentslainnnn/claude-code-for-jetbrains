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

    /**
     * Command substitution: `$(…)` and a backtick pair. Judged unresolvable rather than executed, obviously —
     * evaluating it is exactly what the guard exists to decide about.
     */
    private val SUBSTITUTION = Regex("""\$\(|`[^`]*`""")

    /** A variable reference left standing after expansion, in any spelling the guard understands. The dollar is
     *  `\x24` for the reason [GuardPaths]' own pattern spells it that way — see there; a literal one cannot be
     *  written in a raw string without either the compiler or ktlint objecting. */
    private val RESIDUAL_REF = Regex(
        """\x24\{[A-Za-z_][A-Za-z0-9_]*\}|\x24env:[A-Za-z_][A-Za-z0-9_]*|\x24[A-Za-z_][A-Za-z0-9_]*""" +
            """|%[A-Za-z_][A-Za-z0-9_]*%""",
        RegexOption.IGNORE_CASE,
    )

    /** What a call's destinations turned out to be, once the guard had expanded everything it could. */
    internal class Verdict(val rule: SecurityRule, val text: String)

    /**
     * The first destination whose value the guard could not pin down, with the rule that fits WHY — or null when
     * every destination resolved.
     *
     * The cap is checked before the residue, because the two are not mutually exclusive and the cap is the
     * stronger claim: a cyclic definition also leaves a reference standing, and reporting that as merely
     * "unresolved" would downgrade a hard block to a card.
     */
    internal fun indirectionHit(input: JsonObject, policy: SensitiveGuard.Policy): Verdict? {
        for (raw in ToolInputScanner.destinationCandidates(input)) {
            if (raw.isBlank()) continue
            if (GuardPaths.exceedsEnvDepth(raw, policy.home, policy.envValues)) {
                return Verdict(SecurityRule.RECURSION_LIMIT, raw)
            }
            if (SUBSTITUTION.containsMatchIn(raw)) return Verdict(SecurityRule.UNRESOLVED_VARIABLE, raw)
            val expanded = GuardPaths.expandEnv(raw, policy.home, policy.envValues)
            if (RESIDUAL_REF.containsMatchIn(expanded)) return Verdict(SecurityRule.UNRESOLVED_VARIABLE, raw)
        }
        return null
    }
}
