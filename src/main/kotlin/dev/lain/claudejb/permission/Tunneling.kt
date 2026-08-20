package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

object Tunneling {

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private const val MATCH_EXCERPT_CHARS = 120

    private const val AT = """(?:^|[;&|\n]\s*|\bthen\s+|\bdo\s+)(?:\S*/)?"""

    private const val TOOLS =
        "ngrok|cloudflared|frpc|frps|localtunnel|iodine|iodined|dnscat2|bore|gost|" +
            "proxychains4?|torify|torsocks|pagekite"

    private val CASE_SENSITIVE: List<Regex> = listOf(
        // ssh -R / -D / -L are tunnels; -l (lowercase) is a login name, so this vector must be case-sensitive
        Regex("""$AT""" + """ssh\b[^|;&]*\s-[a-zA-Z]*[RDL]\b"""),
    )

    private val VECTORS: List<Regex> = listOf(
        re(AT + "($TOOLS)(?=\\s|\$|[;&|/])"),
        re(AT + """lt\b[^|;&]*--port\b"""),
        re(AT + """tor(?=\s|$|[;&|])"""),
    )

    internal fun hit(input: JsonObject, home: String? = null, env: Map<String, String> = emptyMap()): String? =
        ToolInputScanner.commandCandidates(input)
            .flatMap { setOf(GuardPaths.expandEnv(it, home, env), CommandRules.deobfuscate(it, home, env)) }
            .firstNotNullOfOrNull { candidate -> firstVector(candidate) }

    private fun firstVector(candidate: String): String? =
        (CASE_SENSITIVE + VECTORS).firstNotNullOfOrNull { it.find(candidate)?.value?.take(MATCH_EXCERPT_CHARS) }
}
