package dev.lain.claudejb.model.permission.scan

import dev.lain.claudejb.model.permission.paths.GuardPaths
import dev.lain.claudejb.model.permission.vocab.MAX_ANALYSIS_DEPTH

internal object CommandDeobfuscation {

    fun deobfuscate(command: String, home: String? = null, env: Map<String, String> = emptyMap()): String {
        var s = command
        var passes = 0
        while (passes++ < MAX_ANALYSIS_DEPTH) {
            val next = peel(s, home, env)
            if (next == s) break
            s = next
        }
        decodePayloads(s).takeIf { it.isNotEmpty() }?.let { s += " " + it.joinToString(" ") }
        expandBraces(s).takeIf { it.isNotEmpty() }?.let { s += " " + it.joinToString(" ") }
        return s
    }

    private val ANSI_C_QUOTED = Regex("""\$'((?:[^'\\]|\\.)*)'""")

    private val ANSI_C_ESCAPE = Regex("""\\(x[0-9A-Fa-f]{1,2}|u[0-9A-Fa-f]{1,4}|[0-7]{1,3}|.)""")

    private fun decodeAnsiC(body: String): String = ANSI_C_ESCAPE.replace(body) { m ->
        val esc = m.groupValues[1]
        when {
            esc.startsWith("x") || esc.startsWith("u") ->
                esc.drop(1).toIntOrNull(16)?.toChar()?.toString() ?: esc

            esc.length in 1..3 && esc.all { it in '0'..'7' } ->
                esc.toIntOrNull(8)?.toChar()?.toString() ?: esc

            else -> when (esc) {
                "n" -> "\n"
                "t" -> "\t"
                "r" -> "\r"
                "0" -> ""
                else -> esc
            }
        }
    }

    private const val MAX_BRACE_EXPANSIONS = 32

    private val BRACE_GROUP = Regex("""\{([^{}\s]*,[^{}\s]*)\}""")

    private val WHITESPACE = Regex("""\s+""")

    private fun expandBraces(command: String): List<String> {
        val out = LinkedHashSet<String>()
        for (token in command.split(WHITESPACE)) {
            if (token.isEmpty() || !BRACE_GROUP.containsMatchIn(token)) continue
            var forms = listOf(token)
            while (forms.size <= MAX_BRACE_EXPANSIONS) {
                val next = forms.flatMap { form ->
                    val group = BRACE_GROUP.find(form) ?: return@flatMap listOf(form)
                    group.groupValues[1].split(',').map { form.replaceRange(group.range, it) }
                }
                if (next == forms) break
                forms = next
            }
            out += forms.filter { it != token }
            if (out.size >= MAX_BRACE_EXPANSIONS) break
        }
        return out.toList()
    }

    private fun peel(command: String, home: String?, env: Map<String, String>): String {
        var s = command
        if ('\\' in s) {
            s = s.replace("\\\n", "").replace("\\\r\n", "")
        }
        if ("$'" in s) {
            s = ANSI_C_QUOTED.replace(s) { m -> decodeAnsiC(m.groupValues[1]) }
        }
        if ('$' in s) {
            s = s.replace(Regex("""\$\{?IFS\}?"""), " ").replace(Regex("""\$'\\(?:x09|011|t)'"""), " ")
        }
        if ('\'' in s || '"' in s || '`' in s) {
            s = s.replace("''", "").replace("\"\"", "").replace("``", "")
        }
        if ('\\' in s) {
            s = WINDOWS_PATH.replace(s) { it.value.replace('\\', '/') }
            s = s.replace(Regex("""\\([A-Za-z0-9._/~-])"""), "$1")
        }
        if ('^' in s) {
            s = s.replace(Regex("""\^(.)"""), "$1")
        }
        if ('\'' in s || '"' in s || '`' in s) {
            s = s.replace(Regex("""["'`]"""), "")
        }
        if ('=' in s) {
            s = substituteAssignments(s)
        }
        s = GuardPaths.expandEnv(s, home, env)
        s = stripFusedExpansions(s)
        return s
    }

    private val WINDOWS_PATH = Regex(
        """(?:(?<![A-Za-z0-9])[A-Za-z]:|(?<![A-Za-z0-9])~|%[A-Za-z_][A-Za-z0-9_]*%)\\[^\s;&|"'<>]*""",
    )

    private val FUSED_EXPANSION = Regex(
        """(?<=[A-Za-z0-9])(?:\x24\{[^{}]*\}|\x24[@*#?!-])""" +
            """|(?:\x24\{[^{}]*\}|\x24[@*#?!-])(?=[A-Za-z0-9])""",
    )

    private fun stripFusedExpansions(command: String): String {
        if ('$' !in command) return command
        var s = command
        var passes = 0
        while (passes++ < MAX_ANALYSIS_DEPTH) {
            val next = FUSED_EXPANSION.replace(s, "")
            if (next == s) break
            s = next
        }
        return s
    }

    fun deobfuscatePath(token: String, home: String? = null, env: Map<String, String> = emptyMap()): String {
        var s = token
        var passes = 0
        while (passes++ < MAX_ANALYSIS_DEPTH) {
            val next = peel(s, home, env)
            if (next == s) break
            s = next
        }
        return s
    }

    private fun truncated(value: String): Boolean =
        value.count { it == '(' } != value.count { it == ')' } ||
            value.count { it == '`' } % 2 != 0 ||
            value.count { it == '{' } != value.count { it == '}' }

    private fun substituteAssignments(command: String): String {
        val assign = Regex("""(?:^|[\s;&|])([A-Za-z_][A-Za-z0-9_]*)=([^\s;&|]+)""")
        val vars = HashMap<String, String>()
        assign.findAll(command).forEach { m ->
            val value = m.groupValues[2]
            if (!truncated(value)) vars[m.groupValues[1]] = value
        }
        if (vars.isEmpty()) return command
        var s = command
        for ((k, v) in vars) {
            val literal = java.util.regex.Matcher.quoteReplacement(v)
            s = s.replace(Regex("""\$\{$k\}"""), literal).replace(Regex("""\$$k(?![A-Za-z0-9_])"""), literal)
        }
        return s
    }

    private const val MAX_DECODED_PAYLOADS = 32

    private val BASE64_RUN = Regex("""[A-Za-z0-9+/]{16,}={0,2}""")

    private val HEX_RUN = Regex("""[0-9a-fA-F]{16,}""")

    private val REV_PIPE = Regex("""\|\s*rev\b""")

    private fun decodePayloads(command: String): List<String> {
        val out = LinkedHashSet<String>()
        var frontier = listOf(command)
        var depth = 0
        while (frontier.isNotEmpty() && depth++ < MAX_ANALYSIS_DEPTH && out.size < MAX_DECODED_PAYLOADS) {
            frontier = frontier.flatMap(::decodeLayer).filter { it != command && out.add(it) }
        }
        return out.toList()
    }

    private fun decodeLayer(text: String): List<String> =
        decodeBase64Payloads(text) + decodeHexPayloads(text) + reversedPayload(text)

    private fun decodeBase64Payloads(command: String): List<String> =
        BASE64_RUN.findAll(command).mapNotNull { m ->
            runCatching { String(java.util.Base64.getDecoder().decode(m.value), Charsets.UTF_8) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() && it.all(::isPrintableAscii) }
        }.toList()

    private fun decodeHexPayloads(command: String): List<String> =
        HEX_RUN.findAll(command).mapNotNull { m ->
            m.value.takeIf { it.length % 2 == 0 }
                ?.let { hex -> runCatching { hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("") }.getOrNull() }
                ?.takeIf { it.isNotBlank() && it.all(::isPrintableAscii) }
        }.toList()

    private fun reversedPayload(command: String): List<String> =
        if (REV_PIPE.containsMatchIn(command)) listOf(command.reversed()) else emptyList()

    private fun isPrintableAscii(c: Char): Boolean = c == '\t' || c == '\n' || c in ' '..'~'
}
