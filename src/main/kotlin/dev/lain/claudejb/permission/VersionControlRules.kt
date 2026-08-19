package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

/**
 * [SecurityRule.VCS_PROTECTION_BYPASS] — a version-control command that **switches off a safeguard the user
 * deliberately put in place**, which is how a secret ends up in history.
 *
 * ### Why this rule is about the bypass and not about the secret
 * The obvious rule to want here is "refuse a commit that contains a credential". The guard cannot implement
 * that one honestly: it sees the command text, not the staged set, and it has no directory listing (only the
 * bounded single-file [SensitiveGuard.Policy.fileReader]). A rule that pretended to know what `git commit`
 * was about to include would be guessing, and a security rule that guesses is worse than none.
 *
 * What it can see exactly is a command asking for a protection to be skipped, and that turns out to be the
 * better target anyway, because it is where the real accidents come from:
 *  - **`git add -f`** exists to defeat `.gitignore`. If a path is ignored, something ignored it on purpose —
 *    very often precisely because it holds a key, a `.env` or a credential dump. Forcing past that is the
 *    single most common way a secret reaches a repository.
 *  - **`--no-verify`** on a commit or a push skips the hooks, and hooks are where secret scanning,
 *    `gitleaks` and lint gates live. The flag's only purpose is to run without the checks.
 *
 * ### What it deliberately does NOT match
 * An ordinary `git add .`, `git add -A` or `git commit -a` is **not** here, and that omission is the whole
 * reason the rule is usable. Those are what everybody types all day, they respect `.gitignore`, and a guard
 * that stopped them would be switched off within an afternoon — taking the two genuinely dangerous flags
 * with it. Same judgement as narrowing [SecurityRule.DESTRUCTIVE_FILESYSTEM] to a catastrophic target.
 *
 * A credential named explicitly (`git add .env`, `git commit -m x secrets.json`) needs nothing from this
 * rule: [CredentialPaths] already matches the path wherever it appears, command tokens included, and
 * reports it as what it is — a credential — which is the more informative wording of the two.
 *
 * Matched on the de-obfuscated and variable-expanded command, exactly like every other command rule.
 */
object VersionControlRules {

    /** A bypass match: the rule that tripped, and the excerpt to quote back. */
    internal data class Hit(val rule: SecurityRule, val text: String)

    /** How much of the matched command is quoted back — enough to recognise, never a whole script. */
    private const val MATCH_EXCERPT_CHARS = 120

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    /**
     * The bypasses, in declaration order.
     *
     * `-f`/`--force` is required to be a **separate flag token** on an `add`/`stage`, so it cannot be
     * satisfied by an unrelated `-f` belonging to another word, and `git push --force` is left to
     * [SecurityRule.DESTRUCTIVE_GIT] where it belongs — that one destroys history rather than leaking.
     */
    private val VECTORS: List<Pair<SecurityRule, Regex>> = listOf(
        // Forcing a path past .gitignore. `add` and its `stage` alias.
        SecurityRule.VCS_PROTECTION_BYPASS to
            re("""\bgit\b[^|;&]*\b(add|stage)\b[^|;&]*\s-(f|-force)(?=\s|$)"""),
        // Skipping the hooks — where secret scanning and lint gates run.
        SecurityRule.VCS_PROTECTION_BYPASS to
            re("""\bgit\b[^|;&]*\b(commit|push)\b[^|;&]*\s--no-verify(?=\s|$)"""),
    )

    /**
     * The first bypass the call trips, or null. Runs over every command candidate and both its expanded and
     * de-obfuscated forms, the same surface [CommandRules.dangerousCommand] is matched against.
     */
    internal fun hit(input: JsonObject, home: String? = null, env: Map<String, String> = emptyMap()): Hit? =
        ToolInputScanner.commandCandidates(input)
            .flatMap { setOf(GuardPaths.expandEnv(it, home, env), CommandRules.deobfuscate(it, home, env)) }
            .firstNotNullOfOrNull { candidate -> firstVector(candidate) }

    /** The first vector [candidate] trips, or null — separate so [hit] stays a flat pipeline. */
    private fun firstVector(candidate: String): Hit? =
        VECTORS.firstNotNullOfOrNull { (rule, pattern) ->
            pattern.find(candidate)?.let { Hit(rule, it.value.take(MATCH_EXCERPT_CHARS)) }
        }
}
