package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

object DestructiveCommands {

    internal data class Hit(val rule: SecurityRule, val text: String)

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private const val MATCH_EXCERPT_CHARS = 120

    private val VECTORS: List<Pair<SecurityRule, Regex>> = listOf(
        SecurityRule.DESTRUCTIVE_IAC to
            re("""\b(terraform|terragrunt|tofu)\b[^|;&]*\b(destroy|apply[^|;&]*-auto-approve|state\s+rm)\b"""),
        SecurityRule.DESTRUCTIVE_IAC to re("""\bpulumi\b[^|;&]*\bdestroy\b"""),
        SecurityRule.DESTRUCTIVE_ORCHESTRATION to
            re("""\bkubectl\b[^|;&]*\b(delete\b[^|;&]*(namespace|--all\b|-A\b)|drain\b)"""),
        SecurityRule.DESTRUCTIVE_ORCHESTRATION to re("""\bhelm\b[^|;&]*\b(uninstall|delete)\b"""),
        SecurityRule.DESTRUCTIVE_CLOUD to re("""\baws\b[^|;&]*\bs3\b[^|;&]*\brb\b[^|;&]*--force"""),
        SecurityRule.DESTRUCTIVE_CLOUD to
            re("""\baws\b[^|;&]*\b(delete-bucket|delete-db-instance|delete-table|terminate-instances)\b"""),
        SecurityRule.DESTRUCTIVE_CLOUD to re("""\b(gcloud|az)\b[^|;&]*\bdelete\b"""),
        SecurityRule.DESTRUCTIVE_DATABASE to re("""\bdrop\s+(database|table|schema)\b"""),
        SecurityRule.DESTRUCTIVE_DATABASE to re("""\btruncate\s+(table\s+)?\w"""),
        SecurityRule.DESTRUCTIVE_DATABASE to re("""\bmysqladmin\b[^|;&]*\bdrop\b"""),
        SecurityRule.DESTRUCTIVE_DATABASE to re("""\bdrop(Database|Collection)\s*\("""),
        SecurityRule.DESTRUCTIVE_DATABASE to re("""\b(FLUSHALL|FLUSHDB)\b"""),
        SecurityRule.DESTRUCTIVE_CONTAINER to
            re("""\b(docker|podman)\b[^|;&]*\b(system\s+prune|volume\s+rm|rm\b[^|;&]*-f)\b"""),
        SecurityRule.DESTRUCTIVE_CONTAINER to re("""\bdocker[- ]compose\b[^|;&]*\bdown\b[^|;&]*(-v\b|--volumes\b)"""),
        SecurityRule.DESTRUCTIVE_GIT to re("""\bgit\b[^|;&]*\bpush\b[^|;&]*(--force\b|-f\b)(?![-\w])"""),
        SecurityRule.DESTRUCTIVE_GIT to re("""\bgit\b[^|;&]*\breset\b[^|;&]*--hard\b"""),
        SecurityRule.DESTRUCTIVE_GIT to re("""\bgit\b[^|;&]*\bclean\b[^|;&]*-\w*f\w*d|\bgit\b[^|;&]*\bclean\b[^|;&]*-\w*d\w*f"""),
        SecurityRule.DESTRUCTIVE_GIT to re("""\bgit\b[^|;&]*\bfilter-(branch|repo)\b"""),
        SecurityRule.DESTRUCTIVE_GIT to re("""\bgit\b[^|;&]*\bbranch\b[^|;&]*\s-D\b"""),
        SecurityRule.DESTRUCTIVE_FILESYSTEM to
            re("""\brm\b[^|;&]*\s-\w*(rf|fr)\w*\b[^|;&]*\s(/|~)\S*"""),
        SecurityRule.DESTRUCTIVE_FILESYSTEM to re("""\brm\b[^|;&]*\s-[rf]\s+-[rf]\b[^|;&]*\s(/|~)\S*"""),
        SecurityRule.DESTRUCTIVE_FILESYSTEM to re("""\bmkfs(\.\w+)?\b"""),
        SecurityRule.DESTRUCTIVE_FILESYSTEM to re("""\bshred\b"""),
        SecurityRule.DESTRUCTIVE_FILESYSTEM to re("""\bdd\b[^|;&]*\bof=/dev/"""),
    )

    internal fun hit(input: JsonObject, home: String? = null, env: Map<String, String> = emptyMap()): Hit? =
        ToolInputScanner.commandCandidates(input)
            .flatMap { setOf(GuardPaths.expandEnv(it, home, env), CommandRules.deobfuscate(it, home, env)) }
            .firstNotNullOfOrNull { candidate -> firstVector(candidate) }

    private fun firstVector(candidate: String): Hit? =
        VECTORS.firstNotNullOfOrNull { (rule, pattern) ->
            pattern.find(candidate)?.let { Hit(rule, it.value.take(MATCH_EXCERPT_CHARS)) }
        }
}
