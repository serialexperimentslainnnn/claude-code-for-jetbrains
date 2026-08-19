package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

object VersionControlRules {

    internal data class Hit(val rule: SecurityRule, val text: String)

    private const val MATCH_EXCERPT_CHARS = 120

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val VECTORS: List<Pair<SecurityRule, Regex>> = listOf(
        SecurityRule.VCS_PROTECTION_BYPASS to
            re("""\bgit\b[^|;&]*\b(add|stage)\b[^|;&]*\s-(f|-force)(?=\s|$)"""),
        SecurityRule.VCS_PROTECTION_BYPASS to
            re("""\bgit\b[^|;&]*\b(commit|push)\b[^|;&]*\s--no-verify(?=\s|$)"""),
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
