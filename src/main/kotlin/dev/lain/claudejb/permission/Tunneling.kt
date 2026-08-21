package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

object Tunneling {

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private const val MATCH_EXCERPT_CHARS = 120

    private val AT = CommandRules.AT_COMMAND

    private const val TOOLS =
        "ngrok|cloudflared|frpc|frps|localtunnel|iodine|iodined|dnscat2|bore|gost|" +
            "proxychains4?|torify|torsocks|pagekite|stunnel|sshuttle|wstunnel|dns2tcp|" +
            "dnstt|ptunnel(-ng)?|hans|udptunnel|httptunnel|hts|htc|corkscrew|proxytunnel|" +
            "microsocks|3proxy|redsocks|rathole|wg-quick"

    private val CASE_SENSITIVE: List<Regex> = listOf(
        // ssh -R / -D / -L / -w / -W / -J are tunnels or pivots; -l (lowercase) is a login name,
        // so this vector must be case-sensitive
        Regex("""$AT""" + """ssh\b[^|;&]*\s-[a-zA-Z]*[RDLwWJ]\b"""),
    )

    private val VECTORS: List<Regex> = listOf(
        re(AT + "($TOOLS)(?=\\s|\$|[;&|/])"),
        re(AT + """ssh\b[^|;&]*-o\s+Proxy(Command|Jump)\b"""),
        re(AT + """wg\s+(set|setconf|setconfig)\b"""),
        re(AT + """openvpn\b[^|;&]*--config\b"""),
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
