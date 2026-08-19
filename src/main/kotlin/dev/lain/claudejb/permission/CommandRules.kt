package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

object CommandRules {

    val DANGEROUS_COMMANDS: List<Regex> = listOf(
        re("""\bgpg2?\b[^|;&]*--export-secret-(keys|subkeys)"""),
        re("""\bssh-keygen\b[^|;&]*\s-y\b"""),
        re("""\bopenssl\b[^|;&]*\b(rsa|ec|pkcs12|pkcs8)\b[^|;&]*-in\b"""),
        re("""\bsecurity\b[^|;&]*\b(dump-keychain|find-(generic|internet)-password)\b"""),
        re("""\b(aws|az|gcloud|oci)\b[^|;&]*\b(configure get|print-access-token|get-token|get-session-token|list-access-tokens)\b"""),
        re("""\bkubectl\b[^|;&]*\bget\b[^|;&]*\bsecret"""),
        re("""\b(docker|podman)\b[^|;&]*\blogin\b[^|;&]*(-p\b|--password\b)"""),
        re("""\bgit\b[^|;&]*\bcredential\b[^|;&]*\bfill\b"""),
        re("""\b(printenv|env|set)\b\s*(\||>|$)"""),
        re("""\bcat\b[^|;&]*\b(shadow|master\.passwd|sudoers)\b"""),
        re("""BEGIN\s+(RSA|OPENSSH|EC|DSA|PGP)\s+PRIVATE\s+KEY"""),
        re("""\b169\.254\.169\.254\b"""),
        re("""\bmetadata\.(google\.internal|azure\.com)\b"""),
        re("""\bcertutil\b[^|;&]*(-exportPFX|-store\b|-user\b|-urlcache\b)"""),
        re("""\b(Export-PfxCertificate|Get-Credential|ConvertFrom-SecureString|Get-ChildItem\s+Cert:)\b"""),
        re("""\breg\b[^|;&]*\b(save|export)\b[^|;&]*hk(lm|cu).*(sam|security|system)"""),
        re("""\b(vaultcmd|cmdkey)\b[^|;&]*(/list|/rlist)"""),
        re("""\bcurl\b[^|;&]*(-T\b|--upload-file\b|-F\b|--data-binary\s*@|--data\s*@)"""),
        re("""\bwget\b[^|;&]*--post-file"""),
        re("""\b(nc|ncat|netcat|socat)\b[^|;&]*(-e\b|\b\d{2,5}\b)"""),
        re("""\b(scp|rsync|sftp)\b[^|;&]*(\.ssh|\.aws|\.gnupg|\.kube|id_rsa|\.pem|\.env)\b"""),
        re("""\b(tar|zip|7z|gzip)\b[^|;&]*(\.ssh|\.aws|\.gnupg|\.kube|id_rsa|\.pem|\.env)\b"""),
        re("""\bbase64\b[^|;&]*(\.ssh|\.aws|\.gnupg|id_rsa|\.pem|\.env)"""),
        re("""\bInvoke-WebRequest\b[^|;&]*-(InFile|Body)\b"""),
        re("""/dev/tcp/\d"""),
        re("""\bdd\b[^|;&]*if=/dev/(sd|nvme|mem|kmem)"""),
        re("""\b(curl|wget)\b[^|]*\|\s*(sudo\s+)?(sh|bash|zsh|python\d?|perl|ruby)\b"""),
        re("""\b(powershell|pwsh)\b[^|;&]*-e(nc|ncodedcommand)?\b\s+[A-Za-z0-9+/=]{16,}"""),
        re("""\b(bitsadmin|mshta|regsvr32|rundll32|installutil|msbuild)\b[^|;&]*(http|/i:|javascript:|scrobj)"""),
    )

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    internal fun cmdStart(names: String) = re("""(?:^|[;&|\n]\s*)(?:sudo\s+)?(?:\S*/)?($names)\b""")

    private const val MATCH_EXCERPT_CHARS = 120

    internal fun dangerousCommand(
        input: JsonObject,
        home: String? = null,
        env: Map<String, String> = emptyMap(),
    ): String? {
        for (command in ToolInputScanner.commandCandidates(input)) {
            for (candidate in setOf(GuardPaths.expandEnv(command, home, env), deobfuscate(command, home, env))) {
                DANGEROUS_COMMANDS.firstOrNull { it.containsMatchIn(candidate) }
                    ?.let { return it.find(candidate)?.value?.take(MATCH_EXCERPT_CHARS) }
            }
        }
        return null
    }

    fun deobfuscate(command: String, home: String? = null, env: Map<String, String> = emptyMap()): String {
        var s = command
        var passes = 0
        while (passes++ < MAX_ANALYSIS_DEPTH) {
            val next = peel(s, home, env)
            if (next == s) break
            s = next
        }
        decodeBase64Payloads(s).takeIf { it.isNotEmpty() }?.let { s += " " + it.joinToString(" ") }
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

    private val BRACE_TOKEN = Regex("""\S*\{[^{}\s]*,[^{}\s]*\}\S*""")

    private fun expandBraces(command: String): List<String> {
        val out = LinkedHashSet<String>()
        for (token in BRACE_TOKEN.findAll(command).map { it.value }) {
            var forms = listOf(token)
            while (forms.size <= MAX_BRACE_EXPANSIONS) {
                val next = forms.flatMap { form ->
                    val group = Regex("""\{([^{}\s]*,[^{}\s]*)\}""").find(form) ?: return@flatMap listOf(form)
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
            s = s.replace(Regex("""\\([A-Za-z0-9._/~-])"""), "$1")
        }
        if ('\'' in s || '"' in s || '`' in s) {
            s = s.replace(Regex("""["'`]"""), "")
        }
        if ('=' in s) {
            s = substituteAssignments(s)
        }
        s = GuardPaths.expandEnv(s, home, env)
        return s
    }

    private fun substituteAssignments(command: String): String {
        val assign = Regex("""(?:^|[\s;&|])([A-Za-z_][A-Za-z0-9_]*)=([^\s;&|]+)""")
        val vars = HashMap<String, String>()
        assign.findAll(command).forEach { vars[it.groupValues[1]] = it.groupValues[2] }
        if (vars.isEmpty()) return command
        var s = command
        for ((k, v) in vars) {
            val literal = java.util.regex.Matcher.quoteReplacement(v)
            s = s.replace(Regex("""\$\{$k\}"""), literal).replace(Regex("""\$$k(?![A-Za-z0-9_])"""), literal)
        }
        return s
    }

    private fun decodeBase64Payloads(command: String): List<String> {
        val out = ArrayList<String>()
        Regex("""[A-Za-z0-9+/]{16,}={0,2}""").findAll(command).forEach { m ->
            runCatching {
                val decoded = String(java.util.Base64.getDecoder().decode(m.value), Charsets.UTF_8)
                if (decoded.isNotBlank() && decoded.all(::isPrintableAscii)) out += decoded
            }
        }
        return out
    }

    private fun isPrintableAscii(c: Char): Boolean = c == '\t' || c == '\n' || c in ' '..'~'
}
