package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

object CommandRules {

    val DANGEROUS_COMMANDS: List<Regex> = listOf(
        re("""\bgpg2?\b[^|;&]*--export-secret-(keys|subkeys)"""),
        re("""\bssh-keygen\b[^|;&]*\s-y\b"""),
        re("""\bopenssl\b[^|;&]*\b(rsa|ec|pkcs12|pkcs8)\b[^|;&]*-in\b"""),
        re("""\bsecurity\b[^|;&]*\b(dump-keychain|find-(generic|internet)-password)\b"""),
        re("""\b(aws|az|gcloud|oci)\b[^|;&]*\b(configure get|print-access-token|get-token|get-session-token|list-access-tokens)\b"""),
        re("""\b(kubectl|oc)\b[^|;&]*\bget\b[^|;&]*\bsecret"""),
        re("""\b(kubectl|oc)\b[^|;&]*\bcreate\s+token\b"""),
        re("""\boc\b[^|;&]*\bextract\b[^|;&]*\bsecret\b"""),
        re("""\boc\b[^|;&]*\bwhoami\b[^|;&]*(-t\b|--show-token\b)"""),
        re("""\boc\b[^|;&]*\bserviceaccounts\b[^|;&]*\b(get-token|new-token)\b"""),
        re("""\baws\b[^|;&]*\bsecretsmanager\b[^|;&]*\b(get-secret-value|batch-get-secret-value)\b"""),
        re("""\baws\b[^|;&]*\bssm\b[^|;&]*\bget-parameters?(-by-path)?\b[^|;&]*--with-decryption\b"""),
        re("""\baws\b[^|;&]*\bkms\b[^|;&]*\b(decrypt|generate-data-key(-pair)?|re-encrypt|get-public-key)\b"""),
        re(
            """\baws\b[^|;&]*\biam\b[^|;&]*\b(create-access-key|create-login-profile|update-login-profile|""" +
                """create-service-specific-credential)\b""",
        ),
        re(
            """\baws\b[^|;&]*\bsts\b[^|;&]*\b(assume-role\S*|assume-root|get-session-token|get-federation-token|""" +
                """get-web-identity-token|get-delegated-access-token)\b""",
        ),
        re(
            """\baws\b[^|;&]*\bec2\b[^|;&]*\b(get-password-data|get-console-output|get-console-screenshot|""" +
                """get-launch-template-data)\b""",
        ),
        re("""\baws\b[^|;&]*\bec2\b[^|;&]*\bdescribe-instance-attribute\b[^|;&]*\buserData\b"""),
        re("""\baws\b[^|;&]*\becr\b[^|;&]*\b(get-login-password|get-authorization-token|get-download-url-for-layer)\b"""),
        re(
            """\baws\b[^|;&]*\bcognito-idp\b[^|;&]*\badmin-(get-user|set-user-password|create-user|initiate-auth|""" +
                """respond-to-auth-challenge)\b""",
        ),
        re("""\baws\b[^|;&]*\bcognito-identity\b[^|;&]*\bget-(credentials-for-identity|open-id-token\S*)\b"""),
        re(
            """\baws\b[^|;&]*\b(sso\b[^|;&]*get-role-credentials|acm\b[^|;&]*export-certificate|""" +
                """redshift\b[^|;&]*get-cluster-credentials\S*|rds\b[^|;&]*generate-db-auth-token|""" +
                """lightsail\b[^|;&]*(get-instance-access-details|download-default-key-pair))\b""",
        ),
        re(
            """\baws\b[^|;&]*\b(apigateway\b[^|;&]*get-api-keys?\b[^|;&]*--include-values?|""" +
                """appsync\b[^|;&]*(list|create)-api-keys?|lambda\b[^|;&]*get-function-configuration)\b""",
        ),
        re("""\bgcloud\b[^|;&]*\bsecrets\b[^|;&]*\bversions\b[^|;&]*\baccess\b"""),
        re("""\bgcloud\b[^|;&]*\bauth\b[^|;&]*\bprint-(access|identity)-token\b"""),
        re("""\bgcloud\b[^|;&]*--impersonate-service-account[= ]"""),
        re("""\bgcloud\b[^|;&]*\biam\b[^|;&]*\bservice-accounts\b[^|;&]*\bkeys\b[^|;&]*\bcreate\b"""),
        re("""\bgcloud\b[^|;&]*\biam\b[^|;&]*\bservice-accounts\b[^|;&]*\bsign-(blob|jwt)\b"""),
        re("""\bgcloud\b[^|;&]*\bkms\b[^|;&]*\b(decrypt|raw-decrypt|asymmetric-decrypt|asymmetric-sign|mac-sign)\b"""),
        re("""\bgcloud\b[^|;&]*\bservices\b[^|;&]*\bapi-keys\b[^|;&]*\bget-key-string\b"""),
        re("""\bgcloud\b[^|;&]*\bcompute\b[^|;&]*\breset-windows-password\b"""),
        re("""\bgcloud\b[^|;&]*\bcontainer\b[^|;&]*\bclusters\b[^|;&]*\bget-credentials\b"""),
        re("""\bgcloud\b[^|;&]*\bsql\b[^|;&]*\bgenerate-login-token\b"""),
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

    /** Command position, shared by every family that anchors a verb. A command runs at the start of the
     *  input, after a separator, after a control keyword, inside a subshell `(` or group `{`, and after any
     *  run of leading `NAME=value` assignments or no-op wrappers (`env`, `nohup`, …). Kept in one place so the
     *  families cannot drift apart, and so closing an evasion here closes it for all of them at once. */
    const val AT_COMMAND: String =
        """(?:^|[;&|\n]\s*|(?<!\x24)[({]\s*|\bthen\s+|\bdo\s+|\bxargs\s+""" +
            """|\b(?:docker|podman|nerdctl|kubectl|oc|crictl)\s+(?:exec|run)\b(?:\s+(?:-\S+|[^\s;&|]+))*?\s+(?:--\s+)?)""" +
            """(?:(?:[A-Za-z_][A-Za-z0-9_]*=[^\s;&|]*|env|nohup|time|nice|command|exec|stdbuf|setsid|ionice""" +
            """|-\S+|\d+)\s+)*""" +
            """(?:\S*/)?"""

    internal fun cmdStart(names: String) = re(AT_COMMAND + """(?:sudo\s+)?($names)\b""")

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
            s = s.replace(Regex("""\\([A-Za-z0-9._/~-])"""), "$1")
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
