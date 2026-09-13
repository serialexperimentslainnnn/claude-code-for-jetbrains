package dev.lain.claudejb.model.session.launch

import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.openapi.util.SystemInfo
import dev.lain.claudejb.util.thisLogger
import java.io.File

object SessionLauncher {

    private val log = thisLogger()

    fun binaryPermissionMode(mode: String): String =
        if (mode == "acceptEdits" || mode == "bypassPermissions") "default" else mode

    fun buildArgs(opts: LaunchOptions, resume: Boolean, mcpConfig: String?): List<String> {
        val args = mutableListOf(
            "--print",
            "--output-format", "stream-json",
            "--input-format", "stream-json",
            "--verbose",
            "--permission-prompt-tool", "stdio",
            "--permission-mode", binaryPermissionMode(opts.permissionMode),
        )
        args += transportFlags(opts)
        args += modelFlags(opts)
        args += toolFilterFlags(opts)
        args += advancedFlags(opts)
        args += appendSystemPromptFlags(systemPrompt(opts))
        mcpConfig?.let { args += listOf("--mcp-config", it) }
        if (resume) {
            opts.sessionId?.let { args += listOf("--resume", it) }
            if (opts.fork && opts.sessionId != null) args += "--fork-session"
        }
        return args
    }

    private fun transportFlags(opts: LaunchOptions): List<String> = buildList {
        if (opts.includePartialMessages) add("--include-partial-messages")
        if (opts.settingSources.isNotBlank()) addAll(listOf("--setting-sources", opts.settingSources))
    }

    private fun modelFlags(opts: LaunchOptions): List<String> = buildList {
        opts.model?.let { addAll(listOf("--model", it)) }
        opts.effort?.let { addAll(listOf("--effort", it)) }
        if (opts.thinkingTokens != null) addAll(listOf("--thinking", "adaptive", "--thinking-display", "summarized"))
    }

    private fun toolFilterFlags(opts: LaunchOptions): List<String> = buildList {
        opts.allowedTools.trim().ifBlank { null }?.let { addAll(listOf("--allowedTools", it)) }
        opts.disallowedTools.trim().ifBlank { null }?.let { addAll(listOf("--disallowedTools", it)) }
    }

    fun appendSystemPromptFlags(prompt: String): List<String> =
        prompt.trim().ifBlank { null }?.let { listOf("--append-system-prompt", it) } ?: emptyList()

    fun systemPrompt(opts: LaunchOptions): String =
        listOf(PluginContextPrompt.TEXT, IdeMcpPrompt.text(ownSockets(opts).keys, opts.ideRules))
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

    fun rulesBlock(opts: LaunchOptions): String = IdeMcpPrompt.rulesBlock(opts.ideRules, ownSockets(opts).keys)

    fun ownSockets(opts: LaunchOptions): Map<IdeServer, String> = if (opts.ideIntegration) opts.ideSockets else emptyMap()

    private fun advancedFlags(opts: LaunchOptions): List<String> = buildList {
        opts.maxTurns?.let { addAll(listOf("--max-turns", it.toString())) }
        opts.maxBudgetUsd?.let { addAll(listOf("--max-budget-usd", it.toString())) }
        opts.fallbackModel?.trim()?.ifBlank { null }?.let { addAll(listOf("--fallback-model", it)) }
        for (dir in opts.addDirs) dir.trim().ifBlank { null }?.let { addAll(listOf("--add-dir", it)) }
        opts.betas?.trim()?.ifBlank { null }?.let { addAll(listOf("--betas", it)) }
        if (opts.strictMcpConfig) add("--strict-mcp-config")
    }

    fun mcpConfigJson(opts: LaunchOptions, helper: McpConfigBuilder.HelperParams? = resolveHelper()): String? =
        McpConfigBuilder.mcpConfigJson(
            customMcpServers = opts.customMcpServers,
            ownSockets = ownSockets(opts),
            helper = helper,
            onCustomParseError = { log.debug { "Failed to parse custom MCP servers JSON: $it" } },
        )

    fun resolveHelper(): McpConfigBuilder.HelperParams? {
        val loader = McpConfigBuilder::class.java.classLoader as? PluginAwareClassLoader
        val lib = loader?.pluginDescriptor?.pluginPath?.resolve("lib")?.toFile()
        if (lib == null || !lib.isDirectory) return null
        return McpConfigBuilder.HelperParams(javaBin(), lib)
    }

    private fun javaBin(): File =
        File(File(System.getProperty("java.home"), "bin"), if (SystemInfo.isWindows) "java.exe" else "java")
}
