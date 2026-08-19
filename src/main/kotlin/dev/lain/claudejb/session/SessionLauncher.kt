package dev.lain.claudejb.session

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.SystemInfo
import dev.lain.claudejb.process.PluginContextPrompt
import dev.lain.claudejb.util.InstalledPlugins
import java.io.File

object SessionLauncher {

    private val log = thisLogger()

    private const val MCP_SERVER_PLUGIN_ID = "com.intellij.mcpServer"

    data class LaunchOptions(
        val model: String?,
        val effort: String?,
        val permissionMode: String,
        val thinkingTokens: Int?,
        val allowedTools: String,
        val disallowedTools: String,
        val settingSources: String,
        val includePartialMessages: Boolean,
        val ideMcpEnabled: Boolean,
        val ideMcpTransport: String,
        val ideMcpPort: Int,
        val customMcpServers: String,
        val sessionId: String?,
        val maxTurns: Int? = null,
        val maxBudgetUsd: Double? = null,
        val fallbackModel: String? = null,
        val addDirs: List<String> = emptyList(),
        val betas: String? = null,
        val strictMcpConfig: Boolean = false,
    )

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
        args += appendSystemPromptFlags(PluginContextPrompt.TEXT)
        mcpConfig?.let { args += listOf("--mcp-config", it) }
        if (resume) opts.sessionId?.let { args += listOf("--resume", it) }
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

    private fun advancedFlags(opts: LaunchOptions): List<String> = buildList {
        opts.maxTurns?.let { addAll(listOf("--max-turns", it.toString())) }
        opts.maxBudgetUsd?.let { addAll(listOf("--max-budget-usd", it.toString())) }
        opts.fallbackModel?.trim()?.ifBlank { null }?.let { addAll(listOf("--fallback-model", it)) }
        for (dir in opts.addDirs) dir.trim().ifBlank { null }?.let { addAll(listOf("--add-dir", it)) }
        opts.betas?.trim()?.ifBlank { null }?.let { addAll(listOf("--betas", it)) }
        if (opts.strictMcpConfig) add("--strict-mcp-config")
    }

    fun mcpConfigJson(opts: LaunchOptions): String? =
        McpConfigBuilder.mcpConfigJson(
            ideMcpEnabled = opts.ideMcpEnabled,
            transport = opts.ideMcpTransport,
            port = opts.ideMcpPort,
            customMcpServers = opts.customMcpServers,
            stdioParams = if (opts.ideMcpEnabled && opts.ideMcpTransport == "stdio") resolveStdioParams(opts) else null,
            onCustomParseError = { log.debug("Failed to parse custom MCP servers JSON", it) },
        )

    fun resolveStdioParams(opts: LaunchOptions): McpConfigBuilder.StdioParams? {
        if (!InstalledPlugins.isEnabled(MCP_SERVER_PLUGIN_ID)) return null
        val pluginLib = findMcpServerLib() ?: return null
        val javaBin = File(File(System.getProperty("java.home"), "bin"), if (SystemInfo.isWindows) "java.exe" else "java")
        return McpConfigBuilder.StdioParams(javaBin, pluginLib, PathManager.getLibPath(), opts.ideMcpPort)
    }

    fun findMcpServerLib(): File? {
        val names = listOf("mcpServer", "mcp-server", "MCP Server")
        val roots = listOfNotNull(
            runCatching { java.nio.file.Paths.get(PathManager.getPluginsPath()) }.getOrNull(),
            runCatching { java.nio.file.Paths.get(PathManager.getPreInstalledPluginsPath()) }.getOrNull(),
        )
        for (root in roots) {
            for (name in names) {
                val lib = root.resolve(name).resolve("lib")
                if (java.nio.file.Files.isDirectory(lib)) return lib.toFile()
            }
        }
        return null
    }
}
