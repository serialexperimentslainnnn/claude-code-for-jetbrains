package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object SensitiveGuard {

    enum class Verdict { ALLOW, ASK, DENY }

    data class Policy(
        val globs: List<String> = CredentialPaths.SENSITIVE_GLOBS,
        val home: String? = null,
        val currentUser: String? = null,
        val guardedRoots: List<String> = emptyList(),
        val wslHost: Boolean = false,
        val projectRoot: String? = null,
        val pathResolver: ((String) -> String?)? = null,
        val envValues: Map<String, String> = emptyMap(),
        val fileReader: ((String) -> String?)? = null,
        val permissiveRules: Set<SecurityRule> = emptySet(),
        val httpProxy: String? = null,
        val httpsProxy: String? = null,
        val noProxyHosts: List<String> = emptyList(),
        val extraBlockedDomains: List<String> = emptyList(),
        val commandWhitelist: List<String> = emptyList(),
        val categoryWhitelist: Map<SecurityCategory, Set<String>> = emptyMap(),
        val ruleWhitelist: Map<SecurityRule, Set<String>> = emptyMap(),
    )

    private const val SETTINGS_PATH = "Settings ▸ Claude Code Security"

    data class Decision(
        val verdict: Verdict,
        val reason: String?,
        val rule: SecurityRule? = null,
        val detail: String? = null,
    )

    fun evaluate(input: JsonObject, policy: Policy): Decision {
        val hit = classify(input, policy) ?: return Decision(Verdict.ALLOW, null)
        liftedByWhitelist(input, hit, policy)?.let { list ->
            return Decision(Verdict.ALLOW, "${hit.text} — allowed by the $list", hit.rule, hit.text)
        }
        return Decision(verdictFor(hit, policy), reasonFor(hit, policy), hit.rule, hit.text)
    }

    private fun verdictFor(hit: Hit, policy: Policy): Verdict =
        if (isEnforced(hit, policy)) Verdict.DENY else Verdict.ASK

    private fun liftedByWhitelist(input: JsonObject, hit: Hit, policy: Policy): String? {
        val issued = ToolInputScanner.commandCandidates(input).map { canonicalCommand(it, policy) }
        if (issued.isEmpty() || issued.any { it.isEmpty() }) return null
        if (liftedBy(issued, policy.ruleWhitelist[hit.rule], policy)) return "whitelist for ${hit.rule.label}"
        if (liftedBy(issued, policy.categoryWhitelist[hit.rule.category], policy)) {
            return "whitelist for ${hit.rule.category.label}"
        }
        if (liftedBy(issued, policy.commandWhitelist, policy)) return "whitelist that applies everywhere"
        return null
    }

    private fun liftedBy(issued: List<String>, allowed: Collection<String>?, policy: Policy): Boolean {
        if (allowed.isNullOrEmpty()) return false
        val approved = allowed.map { canonicalCommand(it, policy) }.filter { it.isNotEmpty() }.toSet()
        if (approved.isEmpty()) return false
        return issued.all { it in approved }
    }

    internal fun canonicalCommand(command: String, policy: Policy): String =
        CommandRules.deobfuscate(command, policy.home, policy.envValues)
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun isEnforced(hit: Hit, policy: Policy): Boolean = hit.rule !in policy.permissiveRules

    private fun reasonFor(hit: Hit, policy: Policy): String =
        if (isEnforced(hit, policy)) {
            "${hit.text} — set this rule to Permissive in $SETTINGS_PATH"
        } else {
            "${hit.text} (asked rather than refused: this rule is Permissive in $SETTINGS_PATH)"
        }

    private data class Hit(val rule: SecurityRule, val text: String)

    private fun classify(input: JsonObject, policy: Policy, depth: Int = 0): Hit? {
        val paths = GuardPaths.expandWithResolved(
            ToolInputScanner.pathCandidates(input, policy.home, policy.envValues),
            policy,
        )
        val projRoot = policy.projectRoot?.let { GuardPaths.fold(GuardPaths.normalize(it, policy.home)) }
        val outsideProject = paths.filter { projRoot == null || !GuardPaths.under(GuardPaths.fold(it), projRoot) }

        return placeRules(paths, outsideProject, policy)
            ?: actionRules(input, policy, depth)
            ?: sinkWriteFindings(input, policy, depth)
            ?: committedHookFindings(input, policy, depth)
            ?: weakRules(input, outsideProject, policy, depth)
    }

    private val PATH_KEY = Regex("""^(file_?path|path|notebook_?path|filename)$""", RegexOption.IGNORE_CASE)

    private val CONTENT_KEY = Regex(
        """^(content|contents|new_?string|new_?str|new_?source)$""",
        RegexOption.IGNORE_CASE,
    )

    private fun stringField(input: JsonObject, key: Regex): String? =
        input.entries.firstOrNull { key.matches(it.key) }
            ?.let { (it.value as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }

    private fun sinkWriteFindings(input: JsonObject, policy: Policy, depth: Int): Hit? {
        if (depth > 0) return null
        val content = stringField(input, CONTENT_KEY)?.takeIf { it.isNotBlank() } ?: return null
        val destination = stringField(input, PATH_KEY)
            ?.let { CommandRules.deobfuscatePath(it, policy.home, policy.envValues) } ?: return null
        if (!ExecutionSinks.isSink(destination)) return null
        val inner = classifyScript(content, policy, depth + 1) ?: return null
        return Hit(inner.rule, "${inner.text} — inside a file that runs when it is used: $destination")
    }

    private val GIT_COMMIT_OR_PUSH = Regex("""\bgit\b[^|;&\n]*\b(commit|push)\b""", RegexOption.IGNORE_CASE)

    private fun committedHookFindings(input: JsonObject, policy: Policy, depth: Int): Hit? {
        if (depth > 0) return null
        val root = policy.projectRoot ?: return null
        val reader = policy.fileReader ?: return null
        val runsGit = ToolInputScanner.commandCandidates(input).any {
            GIT_COMMIT_OR_PUSH.containsMatchIn(CommandRules.deobfuscate(it, policy.home, policy.envValues))
        }
        if (!runsGit) return null
        for (hook in ExecutionSinks.hookFiles(root)) {
            val text = reader(hook)?.takeIf { it.isNotBlank() } ?: continue
            val inner = classifyScript(text, policy, depth + 1) ?: continue
            return Hit(inner.rule, "${inner.text} — inside a git hook that runs on this commit: $hook")
        }
        return null
    }

    private fun placeRules(paths: List<String>, outsideProject: List<String>, policy: Policy): Hit? {
        ForeignTerritory.foreignHit(paths, policy)?.let {
            return Hit(it.rule, "reaches outside your own space: ${it.path}")
        }

        SystemDevices.deviceHit(paths)?.let {
            return Hit(SecurityRule.SYSTEM_DEVICE, "addresses a raw system device: $it")
        }

        val matchers = policy.globs.map { CredentialPaths.compile(it, policy.home) }
        return outsideProject.firstOrNull { p -> matchers.any { it.matches(p) } }
            ?.let { Hit(SecurityRule.CREDENTIALS, "reads credentials or key material outside the project: $it") }
    }

    private fun actionRules(input: JsonObject, policy: Policy, depth: Int): Hit? {
        commandFamilies(input, policy)?.let { return it }

        scriptFindings(input, policy, depth)?.let { return it }

        substitutionFindings(input, policy, depth)?.let { return it }

        if (depth > 0) return null
        return EnvIndirection.indirectionHit(input, policy)?.let {
            val what = if (it.rule == SecurityRule.RECURSION_LIMIT) {
                "hides its destination behind more than $MAX_ANALYSIS_DEPTH levels of variable, or a cycle"
            } else {
                "acts on a destination hidden behind a variable nothing here can resolve"
            }
            Hit(it.rule, "$what: ${it.text}")
        }
    }

    private fun commandFamilies(input: JsonObject, policy: Policy): Hit? =
        coreCommandFamilies(input, policy) ?: defenceCommandFamilies(input, policy)

    private fun coreCommandFamilies(input: JsonObject, policy: Policy): Hit? {
        val families: List<() -> Hit?> = listOf(
            {
                DangerousDomains.blockedHit(ToolInputScanner.urlCandidates(input), policy.extraBlockedDomains)
                    ?.let { Hit(SecurityRule.BLOCKED_DOMAIN, "talks to a known staging or exfiltration service: $it") }
            },
            {
                CommandRules.dangerousCommand(input, policy.home, policy.envValues)
                    ?.let { Hit(SecurityRule.SECRET_DUMPING_COMMANDS, "runs a command that can expose secrets: $it") }
            },
            {
                IntrusionTechniques.hit(input, policy.home, policy.envValues)
                    ?.let { Hit(it.rule, "runs a recognised intrusion technique: ${it.text}") }
            },
            {
                VersionControlRules.hit(input, policy.home, policy.envValues)
                    ?.let { Hit(it.rule, "switches off a version-control safeguard: ${it.text}") }
            },
            {
                DestructiveCommands.hit(input, policy.home, policy.envValues)
                    ?.let { Hit(it.rule, "runs an irreversible destructive operation: ${it.text}") }
            },
            {
                CodeExecution.hit(input, policy.home, policy.envValues)
                    ?.let { Hit(it.rule, "makes this machine run code from elsewhere: ${it.text}") }
            },
        )
        return families.firstNotNullOfOrNull { it() }
    }

    private fun defenceCommandFamilies(input: JsonObject, policy: Policy): Hit? {
        val families: List<() -> Hit?> = listOf(
            {
                AntiForensics.hit(input, policy.home, policy.envValues)
                    ?.let { Hit(SecurityRule.ANTI_FORENSIC, "erases the record of what it did: $it") }
            },
            {
                ResourceHijacking.hit(input, policy.home, policy.envValues)
                    ?.let { Hit(SecurityRule.RESOURCE_HIJACKING, "runs a cryptocurrency miner: $it") }
            },
            {
                InhibitRecovery.hit(input, policy.home, policy.envValues)
                    ?.let { Hit(SecurityRule.INHIBIT_RECOVERY, "destroys the means to recover the system: $it") }
            },
            {
                ContainerEscape.hit(input, policy.home, policy.envValues)
                    ?.let { Hit(SecurityRule.CONTAINER_ESCAPE, "breaks a container out onto the host: $it") }
            },
            {
                Tunneling.hit(input, policy.home, policy.envValues)
                    ?.let { Hit(SecurityRule.TUNNELING, "opens a network tunnel or anonymiser: $it") }
            },
            {
                DisableDefences.hit(input, policy.home, policy.envValues)
                    ?.let { Hit(SecurityRule.DISABLE_DEFENCES, "turns off a security defence: $it") }
            },
            {
                PrivilegeEscalation.hit(input, policy.home, policy.envValues)
                    ?.let { Hit(SecurityRule.PRIVILEGE_ESCALATION, "runs with elevated privileges: $it") }
            },
            {
                ProxyRules.proxyHit(input, policy)
                    ?.let { Hit(SecurityRule.PROXY_BYPASS, "routes around the proxy you declared: $it") }
            },
        )
        return families.firstNotNullOfOrNull { it() }
    }

    private fun weakRules(
        input: JsonObject,
        outsideProject: List<String>,
        policy: Policy,
        depth: Int,
    ): Hit? {
        TempDirs.tempHit(outsideProject)?.let {
            return Hit(SecurityRule.TEMP_DIR, "acts on the system temporary directory: $it")
        }

        val projRoot = policy.projectRoot?.let { GuardPaths.fold(GuardPaths.normalize(it, policy.home)) }

        val writesOutside = projRoot == null || outsideProject.any { GuardPaths.isAbsolute(it) }
        if (depth == 0 && writesOutside) {
            ShellFileWrites.shellFileWrite(input)?.let {
                return Hit(SecurityRule.SHELL_FILE_WRITE, "writes or modifies files outside the project: $it")
            }
        }

        if (projRoot == null) return null
        return ToolInputScanner.locationCandidates(input, policy.home, policy.envValues)
            .mapNotNull { GuardPaths.absoluteForm(it, projRoot) }
            .filterNot { ScriptExecution.inSystemBinDir(it) || SystemDevices.isDeviceNode(it) }
            .firstOrNull { !GuardPaths.under(it, projRoot) }
            ?.let { Hit(SecurityRule.OUTSIDE_PROJECT, "reaches outside the project: $it") }
    }

    private fun scriptFindings(input: JsonObject, policy: Policy, depth: Int): Hit? {
        val scripts = ScriptExecution.scriptsIn(input, policy)
        if (scripts.isEmpty()) return null
        if (depth >= MAX_ANALYSIS_DEPTH) {
            return Hit(SecurityRule.RECURSION_LIMIT, "runs scripts nested deeper than $MAX_ANALYSIS_DEPTH: ${scripts.first()}")
        }
        for (script in scripts) {
            val text = policy.fileReader?.invoke(script)
            if (text == null) {
                if (isExemptDevTool(script)) continue
                return Hit(SecurityRule.SCRIPT_EXECUTION, "runs a script this guard could not read: $script")
            }
            val inner = classifyScript(text, policy, depth + 1) ?: continue
            return Hit(inner.rule, "${inner.text} — inside the script it runs: $script")
        }
        return null
    }

    private fun isExemptDevTool(script: String): Boolean = DevToolScripts.isKnownDevTool(script)

    private val COMMAND_SUBSTITUTION = Regex("""\$\(([^()]*)\)|`([^`]*)`""")

    private fun substitutionFindings(input: JsonObject, policy: Policy, depth: Int): Hit? {
        if (depth >= MAX_ANALYSIS_DEPTH) return null
        for (command in ToolInputScanner.commandCandidates(input)) {
            for (match in COMMAND_SUBSTITUTION.findAll(command)) {
                val inner = match.groupValues[1].ifEmpty { match.groupValues[2] }.trim()
                if (inner.isEmpty()) continue
                val hit = classifyScript(inner, policy, depth + 1) ?: continue
                return Hit(hit.rule, "${hit.text} — inside a command substitution: $inner")
            }
        }
        return null
    }

    private fun classifyScript(text: String, policy: Policy, depth: Int): Hit? =
        classify(buildJsonObject { put("command", text) }, policy, depth)
}
