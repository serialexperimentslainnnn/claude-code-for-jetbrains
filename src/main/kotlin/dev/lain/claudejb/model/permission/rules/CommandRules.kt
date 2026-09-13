package dev.lain.claudejb.model.permission.rules

import dev.lain.claudejb.model.permission.paths.GuardPaths
import dev.lain.claudejb.model.permission.scan.CommandDeobfuscation
import dev.lain.claudejb.model.permission.scan.ToolInputScanner
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

    fun deobfuscate(command: String, home: String? = null, env: Map<String, String> = emptyMap()): String =
        CommandDeobfuscation.deobfuscate(command, home, env)

    fun deobfuscatePath(token: String, home: String? = null, env: Map<String, String> = emptyMap()): String =
        CommandDeobfuscation.deobfuscatePath(token, home, env)
}
