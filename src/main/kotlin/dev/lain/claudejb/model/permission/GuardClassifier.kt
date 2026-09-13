package dev.lain.claudejb.model.permission

import dev.lain.claudejb.model.permission.SensitiveGuard.Policy
import dev.lain.claudejb.model.permission.paths.AlternateDataStreams
import dev.lain.claudejb.model.permission.paths.CredentialPaths
import dev.lain.claudejb.model.permission.paths.DevToolScripts
import dev.lain.claudejb.model.permission.paths.ExecutionSinks
import dev.lain.claudejb.model.permission.paths.ForeignTerritory
import dev.lain.claudejb.model.permission.paths.GuardPaths
import dev.lain.claudejb.model.permission.paths.PathPresence
import dev.lain.claudejb.model.permission.paths.SystemDevices
import dev.lain.claudejb.model.permission.paths.TempDirs
import dev.lain.claudejb.model.permission.rules.CommandRules
import dev.lain.claudejb.model.permission.rules.EnvIndirection
import dev.lain.claudejb.model.permission.rules.ScriptExecution
import dev.lain.claudejb.model.permission.rules.ShellFileWrites
import dev.lain.claudejb.model.permission.scan.ContainerMounts
import dev.lain.claudejb.model.permission.scan.ToolInputScanner
import dev.lain.claudejb.model.permission.vocab.MAX_ANALYSIS_DEPTH
import dev.lain.claudejb.model.permission.vocab.SecurityRule
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object GuardClassifier {

    internal data class Hit(val rule: SecurityRule, val text: String)

    internal fun classify(input: JsonObject, policy: Policy, depth: Int = 0): Hit? {
        val paths = GuardPaths.expandWithResolved(
            ToolInputScanner.pathCandidates(input, policy.home, policy.envValues),
            policy,
        )
        val projRoot = policy.projectRoot?.let { GuardPaths.fold(GuardPaths.normalize(it, policy.home)) }
        val outsideProject = paths.filter {
            projRoot == null || !GuardPaths.under(GuardPaths.fold(it), projRoot, policy.caseInsensitivePaths)
        }

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
        val raw = stringField(input, PATH_KEY) ?: return null
        val destination = listOf(
            CommandRules.deobfuscatePath(raw, policy.home, policy.envValues),
            GuardPaths.normalize(raw, policy.home, policy.envValues),
        ).firstOrNull { ExecutionSinks.isSink(it) } ?: return null
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
        SystemDevices.deviceHit(paths)?.let {
            return Hit(SecurityRule.SYSTEM_DEVICE, "addresses a raw system device: $it")
        }

        ForeignTerritory.foreignHit(paths, policy)?.let {
            return Hit(it.rule, "reaches outside your own space: ${it.path}")
        }

        val matchers = policy.globs.map { CredentialPaths.compile(it, policy.home) }
        return outsideProject.firstOrNull { p -> matchers.any { it.matches(p) } }
            ?.let { Hit(SecurityRule.CREDENTIALS, "reads credentials or key material outside the project: $it") }
            ?: AlternateDataStreams.streamHit(paths)?.let {
                Hit(SecurityRule.SHELL_FILE_WRITE, "addresses an NTFS alternate data stream, which no diff shows: $it")
            }
    }

    private fun actionRules(input: JsonObject, policy: Policy, depth: Int): Hit? {
        GuardCommandFamilies.hit(input, policy)?.let { return it }

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
        val certain = policy.pathProbe == null || commitsToDisk(input)
        val mounted = mountHosts(input, projRoot, policy)
        return ToolInputScanner.locationCandidates(input, policy.home, policy.envValues)
            .mapNotNull { GuardPaths.absoluteForm(it, projRoot) }
            .filterNot { ScriptExecution.inSystemBinDir(it) || SystemDevices.isDeviceNode(it) }
            .firstOrNull {
                !GuardPaths.under(it, projRoot, policy.caseInsensitivePaths) && (certain || it in mounted || present(it, policy))
            }
            ?.let { Hit(SecurityRule.OUTSIDE_PROJECT, "reaches outside the project: $it") }
    }

    private fun commitsToDisk(input: JsonObject): Boolean =
        stringField(input, CONTENT_KEY) != null ||
            ShellFileWrites.shellFileWrite(input) != null ||
            ToolInputScanner.commandCandidates(input).any { ContainerMounts.writesHost(it) }

    private fun mountHosts(input: JsonObject, projRoot: String, policy: Policy): Set<String> =
        ToolInputScanner.commandCandidates(input)
            .flatMap { ContainerMounts.hostSides(CommandRules.deobfuscate(it, policy.home, policy.envValues)) }
            .mapNotNull { GuardPaths.absoluteForm(GuardPaths.normalize(it, policy.home, policy.envValues), projRoot) }
            .toSet()

    private fun present(path: String, policy: Policy): Boolean =
        path.indexOf(':', PATH_LIST_COLON_FROM) >= 0 || policy.pathProbe?.invoke(path) != PathPresence.MISSING

    private const val PATH_LIST_COLON_FROM = 2

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
