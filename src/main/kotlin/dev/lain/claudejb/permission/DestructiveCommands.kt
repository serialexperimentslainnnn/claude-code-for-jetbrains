package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

/**
 * The [SecurityCategory.DESTRUCTIVE_OPERATION] family — the guard's **second axis**: not "an attacker is
 * exfiltrating", but "the agent is about to destroy the user's own systems irreversibly". A `terraform destroy`,
 * a `DROP DATABASE`, a `git push --force` that erases a team's branch. The agent need not be malicious for any of
 * this to happen — a misread task is enough — which is why this family protects the user AND the agent from
 * itself (this session's own guard stopped a stray `rm` and `>` of mine).
 *
 * ### One atom per vector, so disabling one never opens another
 * Each [SecurityRule] here is a single narrow vector, judged by its own pattern, and returned by name. That is
 * the whole point of the granularity: a user who switches off [SecurityRule.DESTRUCTIVE_IAC] to let the agent run
 * `terraform destroy` leaves `DROP DATABASE`, `git push --force` and every other destructive vector fully
 * enforced. A coarse "destructive operations" toggle would be a huge hole the moment it was opened.
 *
 * ### Location-independent, and matched after de-obfuscation
 * Like [CommandRules], a destructive command is dangerous wherever it runs, so nothing here is exempted by the
 * project root, and the command is judged on its [CommandRules.deobfuscate]d form so `t""erraform destroy` and
 * `terraform${'$'}IFSdestroy` are caught too.
 *
 * ### What each pattern is careful NOT to match
 * The negatives are load-bearing: `terraform plan`/`apply` (without a destroy), `kubectl get`/`describe`,
 * `docker ps`, `git status`/`commit`, `helm upgrade`, an ordinary `rm build/` inside the project. Every pattern
 * is anchored to the specific destructive subcommand or flag, never the tool name alone — the same discipline
 * [CommandRules.cmdStart] applies, for the same reason (a rule keyed on a bare tool name fires on a commit
 * message that mentions it).
 */
object DestructiveCommands {

    /** A destructive-command match: which vector rule tripped, and the excerpt to quote back. */
    internal data class Hit(val rule: SecurityRule, val text: String)

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    /** How much of the matched command is quoted back — enough to recognise the rule, not a whole script. */
    private const val MATCH_EXCERPT_CHARS = 120

    /**
     * Each vector, in declaration order = severity order (first match wins the wording). A pattern names the
     * destructive SUBCOMMAND or FLAG, never the bare tool, so the ordinary read/plan/build invocations of the
     * same tools do not trip it.
     */
    private val VECTORS: List<Pair<SecurityRule, Regex>> = listOf(
        // IaC teardown. `terraform destroy`, `apply -auto-approve` (which can carry a destroy in its plan),
        // `state rm` (drops a resource from management), pulumi/terragrunt destroy.
        SecurityRule.DESTRUCTIVE_IAC to
            re("""\b(terraform|terragrunt|tofu)\b[^|;&]*\b(destroy|apply[^|;&]*-auto-approve|state\s+rm)\b"""),
        SecurityRule.DESTRUCTIVE_IAC to re("""\bpulumi\b[^|;&]*\bdestroy\b"""),
        // Kubernetes / Helm. delete namespace/--all/pod-by-force, drain, helm uninstall / rollback.
        SecurityRule.DESTRUCTIVE_ORCHESTRATION to
            re("""\bkubectl\b[^|;&]*\b(delete\b[^|;&]*(namespace|--all\b|-A\b)|drain\b)"""),
        SecurityRule.DESTRUCTIVE_ORCHESTRATION to re("""\bhelm\b[^|;&]*\b(uninstall|delete)\b"""),
        // Cloud resource deletion — remove/terminate/delete of a bucket, instance, db, table.
        SecurityRule.DESTRUCTIVE_CLOUD to re("""\baws\b[^|;&]*\bs3\b[^|;&]*\brb\b[^|;&]*--force"""),
        SecurityRule.DESTRUCTIVE_CLOUD to
            re("""\baws\b[^|;&]*\b(delete-bucket|delete-db-instance|delete-table|terminate-instances)\b"""),
        SecurityRule.DESTRUCTIVE_CLOUD to re("""\b(gcloud|az)\b[^|;&]*\bdelete\b"""),
        // Databases. DROP DATABASE/TABLE, TRUNCATE, mysqladmin drop, Mongo dropDatabase/dropCollection, Redis flush.
        SecurityRule.DESTRUCTIVE_DATABASE to re("""\bdrop\s+(database|table|schema)\b"""),
        SecurityRule.DESTRUCTIVE_DATABASE to re("""\btruncate\s+(table\s+)?\w"""),
        SecurityRule.DESTRUCTIVE_DATABASE to re("""\bmysqladmin\b[^|;&]*\bdrop\b"""),
        SecurityRule.DESTRUCTIVE_DATABASE to re("""\bdrop(Database|Collection)\s*\("""),
        SecurityRule.DESTRUCTIVE_DATABASE to re("""\b(FLUSHALL|FLUSHDB)\b"""),
        // Containers. system prune, volume rm, rm -f, compose down -v (removes named volumes).
        SecurityRule.DESTRUCTIVE_CONTAINER to
            re("""\b(docker|podman)\b[^|;&]*\b(system\s+prune|volume\s+rm|rm\b[^|;&]*-f)\b"""),
        SecurityRule.DESTRUCTIVE_CONTAINER to re("""\bdocker[- ]compose\b[^|;&]*\bdown\b[^|;&]*(-v\b|--volumes\b)"""),
        // Git history loss. force-push, hard reset, clean -fdx, filter rewrite, branch -D.
        SecurityRule.DESTRUCTIVE_GIT to re("""\bgit\b[^|;&]*\bpush\b[^|;&]*(--force\b|-f\b)(?![-\w])"""),
        SecurityRule.DESTRUCTIVE_GIT to re("""\bgit\b[^|;&]*\breset\b[^|;&]*--hard\b"""),
        SecurityRule.DESTRUCTIVE_GIT to re("""\bgit\b[^|;&]*\bclean\b[^|;&]*-\w*f\w*d|\bgit\b[^|;&]*\bclean\b[^|;&]*-\w*d\w*f"""),
        SecurityRule.DESTRUCTIVE_GIT to re("""\bgit\b[^|;&]*\bfilter-(branch|repo)\b"""),
        SecurityRule.DESTRUCTIVE_GIT to re("""\bgit\b[^|;&]*\bbranch\b[^|;&]*\s-D\b"""),
        // Filesystem. `rm -rf` is NARROWED to a catastrophic target — an absolute path, a home, or a root —
        // NOT every recursive delete: `rm -rf node_modules` and `rm -rf build/` are routine dev work, and
        // blocking those is the noise that gets a whole category switched off (the overwhelm this design fights).
        // A recursive force-delete of `/…`, `~/…` or an absolute path is the catastrophe; a relative in-project
        // path is left to the ordinary flow. The regex matches a target that begins `/` or `~`; a `$HOME`/`${'$'}{HOME}`
        // target is already resolved to `/home/…` by [GuardPaths.expandEnv] (hit() runs over the expanded form),
        // so it lands on the `/` branch without the literal needing to appear here. `-rf`/`-fr`, combined or split.
        SecurityRule.DESTRUCTIVE_FILESYSTEM to
            re("""\brm\b[^|;&]*\s-\w*(rf|fr)\w*\b[^|;&]*\s(/|~)\S*"""),
        SecurityRule.DESTRUCTIVE_FILESYSTEM to re("""\brm\b[^|;&]*\s-[rf]\s+-[rf]\b[^|;&]*\s(/|~)\S*"""),
        SecurityRule.DESTRUCTIVE_FILESYSTEM to re("""\bmkfs(\.\w+)?\b"""),
        SecurityRule.DESTRUCTIVE_FILESYSTEM to re("""\bshred\b"""),
        SecurityRule.DESTRUCTIVE_FILESYSTEM to re("""\bdd\b[^|;&]*\bof=/dev/"""),
    )

    /**
     * The first destructive vector the command trips, or null. Runs over every command candidate and its
     * de-obfuscated form, exactly as [CommandRules.dangerousCommand] does.
     *
     * `rm -rf` is judged by its TARGET, not by the flag alone: a recursive force-delete of a root, a home or an
     * absolute path is the catastrophe worth a card, while `rm -rf node_modules` / `rm -rf build/` — a relative
     * path inside the project — is left to the ordinary flow. That asymmetry is deliberate: blocking every
     * recursive delete is the noise that gets a whole category switched off, which is the exact over-permissiveness
     * the fine granularity exists to prevent. [SecurityRule.DESTRUCTIVE_FILESYSTEM] still fires unconditionally on
     * the whole-disk primitives ([re] `mkfs`/`shred`/`dd of=/dev/`), which have no benign in-project form.
     */
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
