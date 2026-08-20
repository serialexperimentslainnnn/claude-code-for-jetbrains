package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

object PrivilegeEscalation {

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private const val MATCH_EXCERPT_CHARS = 120

    private const val AT_COMMAND = """(?:^|[;&|\n]\s*|\bthen\s+|\bdo\s+|\bxargs\s+)(?:\S*/)?"""

    private const val WHOLE_WORD = """(?=\s|$|[;&|])"""

    private val VECTORS: List<Regex> = listOf(
        re(
            AT_COMMAND +
                """(?:sudo|sudoedit|doas|pkexec|runuser|setpriv|gksudo|gksu|kdesudo|kdesu|run0|su)""" +
                WHOLE_WORD,
        ),
        re("""\bosascript\b[^;&|\n]*with\s+administrator\s+privileges"""),
        re(AT_COMMAND + """runas$WHOLE_WORD[^;&|\n]*/user:"""),
        re("""\bStart-Process\b[^;&|\n]*-Verb\s+RunAs\b"""),
        re(AT_COMMAND + """psexec(?:64)?(?:\.exe)?$WHOLE_WORD"""),
        re("""\bwsl(?:\.exe)?\b[^;&|\n]*(?:-u|--user)\s+root\b"""),
    )

    internal fun hit(input: JsonObject, home: String? = null, env: Map<String, String> = emptyMap()): String? =
        ToolInputScanner.commandCandidates(input)
            .flatMap { setOf(GuardPaths.expandEnv(it, home, env), CommandRules.deobfuscate(it, home, env)) }
            .firstNotNullOfOrNull { candidate -> firstVector(candidate) }

    private fun firstVector(candidate: String): String? =
        VECTORS.firstNotNullOfOrNull { it.find(candidate)?.value?.trim()?.take(MATCH_EXCERPT_CHARS) }
}
