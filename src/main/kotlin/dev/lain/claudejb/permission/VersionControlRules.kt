package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

object VersionControlRules {

    internal data class Hit(val rule: SecurityRule, val text: String)

    private const val MATCH_EXCERPT_CHARS = 120

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val VECTORS: List<Pair<SecurityRule, Regex>> = listOf(
        SecurityRule.VCS_PROTECTION_BYPASS to
            re("""\bgit\b[^|;&\n]*\b(add|stage)\b[^|;&\n]*\s-(f|-force)(?=\s|$|[;&|])"""),
        SecurityRule.VCS_PROTECTION_BYPASS to
            re("""\bgit\b[^|;&\n]*\b(commit|push|merge)\b[^|;&\n]*\s--no-verify(?=\s|$|[;&|])"""),
        SecurityRule.VCS_PROTECTION_BYPASS to
            re("""\bgit\b[^|;&\n]*\bcommit\b[^|;&\n]*\s-n(?=\s|$|[;&|])"""),
        SecurityRule.VCS_PROTECTION_BYPASS to
            re("""\bgit\b[^|;&\n]*\b(commit|tag)\b[^|;&\n]*--no-gpg-sign(?=\s|$|[;&|])"""),
        SecurityRule.VCS_PROTECTION_BYPASS to
            re("""\bgit\b[^|;&\n]*-c\s+(commit|tag)\.gpgsign=(false|0|no|off)\b"""),
        SecurityRule.VCS_PROTECTION_BYPASS to
            re("""\bgit\b[^|;&\n]*-c\s+gpg\.program="""),
        SecurityRule.VCS_PROTECTION_BYPASS to
            re("""\bgit\b[^|;&\n]*-c\s+core\.hooksPath="""),
        SecurityRule.VCS_PROTECTION_BYPASS to
            re("""\bGIT_CONFIG_KEY_\d+=(core\.hooksPath|commit\.gpgsign|tag\.gpgsign)\b"""),
        SecurityRule.VCS_PROTECTION_BYPASS to
            re("""\bSKIP=\S+\s+git\s+commit\b"""),
        SecurityRule.VCS_PROTECTION_BYPASS to
            re("""\bPRE_COMMIT_ALLOW_NO_CONFIG="""),
        SecurityRule.VCS_PROTECTION_BYPASS to
            re("""\bHUSKY=0\b"""),
    )

    internal fun hit(input: JsonObject, home: String? = null, env: Map<String, String> = emptyMap()): Hit? =
        ToolInputScanner.commandCandidates(input)
            .flatMap { setOf(GuardPaths.expandEnv(it, home, env), CommandRules.deobfuscate(it, home, env)) }
            .firstNotNullOfOrNull { candidate -> firstVector(candidate) }

    private fun firstVector(candidate: String): Hit? =
        VECTORS.firstNotNullOfOrNull { (rule, pattern) ->
            pattern.find(candidate)?.let { Hit(rule, it.value.take(MATCH_EXCERPT_CHARS)) }
        }
}
