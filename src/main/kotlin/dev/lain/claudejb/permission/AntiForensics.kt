package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

object AntiForensics {

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private const val MATCH_EXCERPT_CHARS = 120

    private const val AT = """(?:^|[;&|\n]\s*|\bthen\s+|\bdo\s+)"""

    private val VECTORS: List<Regex> = listOf(
        re(AT + """history\s+-c(?=\s|$|[;&|])"""),
        re(AT + """unset\s+HISTFILE\b"""),
        re(AT + """set\s+\+o\s+history\b"""),
        re("""\bHISTFILE=/dev/null\b"""),
        re(AT + """journalctl\b[^|;&]*--vacuum-(time|size|files)\b"""),
        re(AT + """Clear-History(?=\s|$|[;&|])"""),
        re(AT + """Set-PSReadlineOption\b[^|;&]*-HistorySaveStyle\s+SaveNothing\b"""),
        re(AT + """wevtutil\b[^|;&]*\bcl\b"""),
        re(AT + """Clear-EventLog(?=\s|$|[;&|])"""),
    )

    internal fun hit(input: JsonObject, home: String? = null, env: Map<String, String> = emptyMap()): String? =
        ToolInputScanner.commandCandidates(input)
            .flatMap { setOf(GuardPaths.expandEnv(it, home, env), CommandRules.deobfuscate(it, home, env)) }
            .firstNotNullOfOrNull { candidate -> firstVector(candidate) }

    private fun firstVector(candidate: String): String? =
        VECTORS.firstNotNullOfOrNull { it.find(candidate)?.value?.take(MATCH_EXCERPT_CHARS) }
}
