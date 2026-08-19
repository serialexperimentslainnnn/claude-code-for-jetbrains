package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

object EnvIndirection {

    /** A variable reference left standing after expansion, in any spelling the guard understands. The dollar is
     *  `\x24` for the reason [GuardPaths]' own pattern spells it that way — see there; a literal one cannot be
     *  written in a raw string without either the compiler or ktlint objecting. */
    private val RESIDUAL_REF = Regex(
        """\x24\{[A-Za-z_][A-Za-z0-9_]*\}|\x24env:[A-Za-z_][A-Za-z0-9_]*|\x24[A-Za-z_][A-Za-z0-9_]*""" +
            """|%[A-Za-z_][A-Za-z0-9_]*%""",
        RegexOption.IGNORE_CASE,
    )

    private val FOR_VAR = Regex("""\bfor\s+(?:\(\(\s*)?([A-Za-z_][A-Za-z0-9_]*)\b""")
    private val READ_STMT = Regex("""\bread\b([^;&|\n]*)""")
    private val LOCAL_ASSIGN = Regex("""(?:^|[\s;&|(])([A-Za-z_][A-Za-z0-9_]*)=""")

    internal class Verdict(val rule: SecurityRule, val text: String)

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

    private fun refName(ref: String): String? =
        Regex("""[A-Za-z_][A-Za-z0-9_]*""").findAll(ref).lastOrNull()?.value

    internal fun indirectionHit(input: JsonObject, policy: SensitiveGuard.Policy): Verdict? {
        val bound = locallyBoundNames(ToolInputScanner.commandCandidates(input))
        for (raw in ToolInputScanner.destinationCandidates(input)) {
            if (raw.isBlank()) continue
            if (GuardPaths.exceedsEnvDepth(raw, policy.home, policy.envValues)) {
                return Verdict(SecurityRule.RECURSION_LIMIT, raw)
            }
            val expanded = GuardPaths.expandEnv(raw, policy.home, policy.envValues)
            val unresolvedExternal = RESIDUAL_REF.findAll(expanded)
                .mapNotNull { refName(it.value) }
                .any { it !in bound }
            if (unresolvedExternal) return Verdict(SecurityRule.UNRESOLVED_VARIABLE, raw)
        }
        return null
    }
}
