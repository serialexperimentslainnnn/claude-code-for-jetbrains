package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

object CodeExecution {

    internal data class Hit(val rule: SecurityRule, val text: String)

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private const val MATCH_EXCERPT_CHARS = 120

    private val VECTORS: List<Pair<SecurityRule, Regex>> = listOf(
        SecurityRule.PACKAGE_INSTALL_HOOK to
            re("""\b(npm|pnpm|yarn|bun)\b[^|;&]*\b(install|add|i)\b"""),
        SecurityRule.PACKAGE_INSTALL_HOOK to re("""\bpip3?\b[^|;&]*\binstall\b"""),
        SecurityRule.PACKAGE_INSTALL_HOOK to re("""\b(gem|cargo|go|composer|poetry|bundle)\b[^|;&]*\b(install|add|get)\b"""),
        SecurityRule.PACKAGE_INSTALL_HOOK to re("""\bcurl\b[^|]*\|\s*(sudo\s+)?(npm|pip3?|gem|bash|sh)\b"""),
        SecurityRule.PERSISTENCE_MECHANISM to re("""\bcrontab\b\s+(-(?=\s|$)|[^-\s])"""),
        SecurityRule.PERSISTENCE_MECHANISM to re("""(?:^|[;&|\n]\s*)at\s+\w"""),
        SecurityRule.PERSISTENCE_MECHANISM to re("""\bsystemctl\b[^|;&]*\b(enable|start)\b[^|;&]*\.timer\b"""),
        SecurityRule.PERSISTENCE_MECHANISM to re("""\bgit\b[^|;&]*\bconfig\b[^|;&]*\bcore\.hooksPath\b"""),
        SecurityRule.PERSISTENCE_MECHANISM to re("""\.git/hooks/"""),
        SecurityRule.CODE_INJECTION to re("""\b(LD_PRELOAD|LD_LIBRARY_PATH|DYLD_INSERT_LIBRARIES)\s*="""),
    )

    internal fun hit(input: JsonObject, home: String? = null, env: Map<String, String> = emptyMap()): Hit? =
        ToolInputScanner.commandCandidates(input)
            .flatMap { setOf(GuardPaths.expandEnv(it, home, env), CommandRules.deobfuscate(it, home, env)) }
            .firstNotNullOfOrNull { candidate -> firstVector(candidate) }

    /** The first vector [candidate] trips, or null — kept separate so [hit] stays a flat pipeline (detekt nesting). */
    private fun firstVector(candidate: String): Hit? =
        VECTORS.firstNotNullOfOrNull { (rule, pattern) ->
            pattern.find(candidate)?.let { Hit(rule, it.value.take(MATCH_EXCERPT_CHARS)) }
        }
}
