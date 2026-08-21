package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

object DestructiveCommands {

    internal data class Hit(val rule: SecurityRule, val text: String)

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private const val MATCH_EXCERPT_CHARS = 120

    private const val SEG = """[^|;&]*"""

    private const val AT_COMMAND = """(?:^|[;&|\n]\s*|\bthen\s+|\bdo\s+|\bxargs\s+)(?:\S*/)?"""

    private val IAC: List<Regex> = listOf(
        re("""\b(terraform|terragrunt|tofu)\b$SEG\b(destroy|apply$SEG-auto-approve|state\s+rm|workspace\s+delete)\b"""),
        re("""\b(pulumi|cdk|cdktf)\b$SEG\bdestroy\b"""),
        re("""\bserverless\b$SEG\bremove\b"""),
        re("""\bsam\b$SEG\bdelete\b"""),
        re("""\bvagrant\b$SEG\bdestroy\b"""),
        re("""\bansible(-playbook)?\b$SEG\bstate\s*=\s*absent\b"""),
    )

    private val ORCHESTRATION: List<Regex> = listOf(
        re(
            """\b(kubectl|oc)\b$SEG\bdelete\b$SEG\b(namespace|ns|project|projects|pvc|persistentvolume|secret|""" +
                """statefulset|deployment|crd|customresourcedefinition)\b""",
        ),
        re("""\b(kubectl|oc)\b$SEG\bdelete\b$SEG(--all\b|-A\b|--all-namespaces\b)"""),
        re("""\b(kubectl|oc)\b$SEG\b(drain|cordon)\b"""),
        re("""\b(kubectl|oc)\b$SEG\breplace\b$SEG--force\b"""),
        re("""\b(kubectl|oc)\b$SEG\bscale\b$SEG--replicas\s*=?\s*0\b"""),
        re("""\bhelm\b$SEG\b(uninstall|delete)\b"""),
        re("""\bargocd\b$SEG\bapp\s+delete\b"""),
        re("""\bflux\b$SEG\bdelete\b"""),
        re("""\bnomad\b$SEG\bjob\s+stop\b$SEG-purge\b"""),
        re("""\b(eksctl|kind|k3d)\b$SEG\bdelete\b$SEG\bcluster\b"""),
        re("""\bminikube\b$SEG\bdelete\b"""),
        re("""\bdocker\b$SEG\bswarm\s+leave\b$SEG--force\b"""),
    )

    private val CLOUD: List<Regex> = listOf(
        re("""\baws\b$SEG\bs3\b$SEG\brb\b$SEG--force"""),
        re("""\baws\b$SEG\bs3\b$SEG\brm\b$SEG--recursive"""),
        re("""\baws\b$SEG\b(delete|terminate|deregister|destroy)-[a-z-]+\b"""),
        re("""\b(gcloud|gsutil|az|doctl|flyctl|wrangler|vercel|render|railway)\b$SEG\b(delete|destroy|rm|remove)\b"""),
        re("""\bheroku\b$SEG\bapps:destroy\b"""),
        re("""\bvault\b$SEG\b(delete|destroy)\b"""),
        re("""\bop\b$SEG\bitem\s+delete\b"""),
    )

    private val DATABASE: List<Regex> = listOf(
        re("""\bdrop\s+(database|table|schema|index|user|role|collection|keyspace)\b"""),
        re("""\btruncate\s+(table\s+)?\w"""),
        re("""\bdelete\s+from\s+[\w."\[\]`]+\s*(;|$|["'])"""),
        re("""\b(dropdb|dropuser|pg_dropcluster)\b"""),
        re("""\bmysqladmin\b$SEG\bdrop\b"""),
        re("""\bdrop(Database|Collection)\s*\("""),
        re("""\b(FLUSHALL|FLUSHDB)\b"""),
        re("""\b(prisma\s+migrate\s+reset|rails\s+db:drop|alembic\s+downgrade\s+base|artisan\s+migrate:fresh)\b"""),
    )

    private val CONTAINER: List<Regex> = listOf(
        re("""\b(docker|podman|nerdctl)\b$SEG\b(system\s+prune|system\s+reset|volume\s+rm|volume\s+prune)\b"""),
        re("""\b(docker|podman|nerdctl)\b$SEG\b(rm|rmi)\b$SEG-\w*f"""),
        re("""\b(docker|podman)\b$SEG\bimage\s+prune\b$SEG-\w*a"""),
        re("""\bdocker[- ]compose\b$SEG\bdown\b$SEG(-v\b|--volumes\b)"""),
    )

    private val GIT: List<Regex> = listOf(
        re("""\bgit\b$SEG\bpush\b$SEG(--force\b(?!-with-lease)|-f\b(?![-\w])|--delete\b)"""),
        re("""\bgit\b$SEG\breset\b$SEG--hard\b"""),
        re("""\bgit\b$SEG\bclean\b$SEG-\w*f\w*d|\bgit\b$SEG\bclean\b$SEG-\w*d\w*f"""),
        re("""\bgit\b$SEG\bfilter-(branch|repo)\b"""),
        re("""\bgit\b$SEG\bbranch\b$SEG\s-D\b"""),
        re("""\bgit\b$SEG\bstash\b$SEG\b(clear|drop)\b"""),
        re("""\bgit\b$SEG\breflog\b$SEG\bexpire\b"""),
        re("""\bgit\b$SEG\bworktree\s+remove\b$SEG--force\b"""),
        re("""\bgit\b$SEG\bcheckout\b$SEG\s(--\s+)?\.\s*($|[;&|])"""),
        re("""\bgit\b$SEG\brestore\b$SEG\s(--\s+)?\.\s*($|[;&|])"""),
    )

    private val FILESYSTEM: List<Regex> = listOf(
        re("""\brm\b$SEG\s-\w*(rf|fr)\w*\b$SEG\s(/|~)\S*"""),
        re("""\brm\b$SEG\s-[rf]\s+-[rf]\b$SEG\s(/|~)\S*"""),
        re("""\brsync\b$SEG--delete\b"""),
        re("""\bfind\b$SEG\s(-delete\b|-exec\s+(rm|shred)\b)"""),
        re("""\bmkfs(\.\w+)?\b"""),
        re("""\b(shred|wipefs|blkdiscard|sgdisk|fdisk|parted)\b"""),
        re("""\bdd\b$SEG\bof=/dev/"""),
        re("""\bzfs\b$SEG\bdestroy\b"""),
        re("""\b(lvremove|vgremove|pvremove)\b"""),
        re("""\bbtrfs\b$SEG\bsubvolume\s+delete\b"""),
        re("""\bdiskutil\b$SEG\berase(Disk|Volume)\b"""),
        re("""\bchmod\b$SEG-\w*R\w*\b$SEG\s(777|000)\b$SEG\s/"""),
        re(AT_COMMAND + """(del|rd|rmdir)\b$SEG\s/[sS]\b"""),
        re("""\bformat\b\s+[a-zA-Z]:"""),
        re("""\b(diskpart|Clear-Disk|Format-Volume|Clear-RecycleBin)\b"""),
        re("""\bRemove-Item\b$SEG-Recurse\b"""),
        re("""\bvssadmin\b$SEG\bdelete\s+shadows\b"""),
        re("""\bwmic\b$SEG\bshadowcopy\b$SEG\bdelete\b"""),
        re("""\bcipher\b$SEG\s/w\b"""),
        re("""\bbcdedit\b$SEG\s/delete\b"""),
    )

    private val VECTORS: List<Pair<SecurityRule, Regex>> =
        IAC.map { SecurityRule.DESTRUCTIVE_IAC to it } +
            ORCHESTRATION.map { SecurityRule.DESTRUCTIVE_ORCHESTRATION to it } +
            CLOUD.map { SecurityRule.DESTRUCTIVE_CLOUD to it } +
            DATABASE.map { SecurityRule.DESTRUCTIVE_DATABASE to it } +
            CONTAINER.map { SecurityRule.DESTRUCTIVE_CONTAINER to it } +
            GIT.map { SecurityRule.DESTRUCTIVE_GIT to it } +
            FILESYSTEM.map { SecurityRule.DESTRUCTIVE_FILESYSTEM to it }

    internal fun hit(input: JsonObject, home: String? = null, env: Map<String, String> = emptyMap()): Hit? =
        ToolInputScanner.commandCandidates(input)
            .flatMap { setOf(GuardPaths.expandEnv(it, home, env), CommandRules.deobfuscate(it, home, env)) }
            .firstNotNullOfOrNull { candidate -> firstVector(candidate) }

    private fun firstVector(candidate: String): Hit? =
        VECTORS.firstNotNullOfOrNull { (rule, pattern) ->
            pattern.find(candidate)?.let { Hit(rule, it.value.take(MATCH_EXCERPT_CHARS)) }
        }
}
