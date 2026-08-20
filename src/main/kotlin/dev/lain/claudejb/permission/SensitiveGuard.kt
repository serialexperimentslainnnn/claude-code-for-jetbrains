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
        /**
         * The rules running in **Permissive** mode: detection still happens, and a hit becomes a card
         * instead of a refusal. Every rule not in here is **Enforcing**, which is the default for all of
         * them — an empty set is the original hard lock exactly.
         */
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
        /**
         * What the rule actually saw, as its own verb phrase and the excerpt that tripped it — no settings
         * path, no verdict, no advice. [reason] is that dressed for one audience; anything that has to
         * explain the same match to a different one needs the bare sentence rather than a substring of it.
         */
        val detail: String? = null,
    )

    fun evaluate(input: JsonObject, policy: Policy): Decision {
        val hit = classify(input, policy) ?: return Decision(Verdict.ALLOW, null)
        // An ALLOW that came from a whitelist carries the rule and the list that lifted it, unlike the ALLOW
        // above: the difference between "nothing matched" and "something matched and you permitted it" is
        // what lets the transcript warn about the second one instead of staying silent.
        liftedByWhitelist(input, hit, policy)?.let { list ->
            return Decision(Verdict.ALLOW, "${hit.text} — allowed by the $list", hit.rule, hit.text)
        }
        return Decision(verdictFor(hit, policy), reasonFor(hit, policy), hit.rule, hit.text)
    }

    private fun verdictFor(hit: Hit, policy: Policy): Verdict =
        if (isEnforced(hit, policy)) Verdict.DENY else Verdict.ASK

    /**
     * Whether the user has already said this exact command may run.
     *
     * Asked **narrowest first** — the rule that fired, then that rule's category, then the global list — so
     * the permission can be attributed to one entry rather than to "somewhere". Every command the call
     * issues has to be covered: authorising `terraform destroy` does not authorise
     * `terraform destroy && rm -rf /`, which is a different string.
     *
     * There is no rule this cannot lift, deliberately. A false positive the user cannot get past stops work
     * the user asked for, and deciding which of their own commands they are allowed to permit is not this
     * code's call — [SecurityRule.whitelistable] survives only as the flag that decides whether adding one
     * from a block warns first.
     */
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

        // in one function is neither reviewable nor within detekt's limits — and the order across them is exactly
        return placeRules(paths, outsideProject, policy)
            ?: actionRules(input, policy, depth)
            ?: weakRules(input, outsideProject, policy, depth)
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

    /**
     * The families recognised by the SHAPE OF A COMMAND rather than by a path — asked in severity order, first
     * hit wins the wording.
     *
     * Split out of [actionRules] rather than inlined there, and the split is not only detekt's return-count
     * budget: this is the list that grows. Every new command family is one more entry here and one more file
     * beside this one, which is the package's own rule — a rule is a file, never a branch in the verdict — and
     * keeping them together is what lets the ordering be READ as an ordering instead of reconstructed from a
     * chain of early returns interleaved with the opaque rules and the recursion bound.
     *
     * The order, and why each step is where it is:
     *  1. a **blocked destination** — reads as strongly as a credential dump and more specifically than "a
     *     dangerous command": a call that is both `curl --upload-file` AND aimed at a paste site is best
     *     described by the site;
     *  2. a **secret-dumping command** — the actual secret leaving;
     *  3. a **version-control safeguard being skipped** — a door left open, which is weaker than one already
     *     walked through;
     *  4. a **destructive operation** — not confidentiality at all, but "this is about to delete your production
     *     database" outranks every remaining claim about a command's shape;
     *  5. **code execution / persistence** — someone else's code, now or after the session;
     *  6. a **proxy bypass** — the narrowest of the set, worth saying only when nothing worse is true.
     */
    private fun commandFamilies(input: JsonObject, policy: Policy): Hit? {
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
            .filter { GuardPaths.isAbsolute(it) }
            .map { GuardPaths.fold(it) }
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
            if (isExemptDevTool(script)) continue
            val text = policy.fileReader?.invoke(script)
                ?: return Hit(SecurityRule.SCRIPT_EXECUTION, "runs a script this guard could not read: $script")
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
