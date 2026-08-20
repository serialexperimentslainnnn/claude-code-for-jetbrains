package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

object InhibitRecovery {

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private const val MATCH_EXCERPT_CHARS = 120

    private const val SEG = """[^|;&]*"""

    private val VECTORS: List<Regex> = listOf(
        re("""\bwbadmin(\.exe)?\b$SEG\bdelete\b"""),
        re("""\bbcdedit(\.exe)?\b$SEG\brecoveryenabled\s+no\b"""),
        re("""\bbcdedit(\.exe)?\b$SEG\bbootstatuspolicy\s+ignoreallfailures\b"""),
        re("""\bvssadmin(\.exe)?\b$SEG\bresize\s+shadowstorage\b"""),
        re("""\bWin32_Shadowcopy\b[^;&\n]*\b(delete|remove)\b"""),
        re("""\bdiskshadow(\.exe)?\b$SEG\bdelete\s+shadows\b"""),
        re("""\bDisable-ComputerRestore\b"""),
        re("""\bschtasks(\.exe)?\b${SEG}SystemRestore$SEG\bdisable\b"""),
        re("""\btmutil\s+disable\b"""),
    )

    internal fun hit(input: JsonObject, home: String? = null, env: Map<String, String> = emptyMap()): String? =
        ToolInputScanner.commandCandidates(input)
            .flatMap { setOf(GuardPaths.expandEnv(it, home, env), CommandRules.deobfuscate(it, home, env)) }
            .firstNotNullOfOrNull { candidate -> firstVector(candidate) }

    private fun firstVector(candidate: String): String? =
        VECTORS.firstNotNullOfOrNull { it.find(candidate)?.value?.take(MATCH_EXCERPT_CHARS) }
}
