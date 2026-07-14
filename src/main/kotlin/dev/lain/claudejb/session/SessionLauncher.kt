package dev.lain.claudejb.session

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.SystemInfo
import dev.lain.claudejb.util.InstalledPlugins
import java.io.File

/**
 * Pure(-ish) assembly of the `claude` process launch: the CLI argument vector and the `--mcp-config` JSON, lifted
 * verbatim out of [ClaudeSession] so the launch contract can be unit-tested in isolation. Every input arrives as an
 * immutable [LaunchOptions] snapshot — the session captures its volatile state once and hands it here, so no
 * session/platform mutation can race the build.
 *
 * - [buildArgs] is PURE: identical inputs → identical output; no IDE coupling. The argument order and the flags must
 *   match the historical [ClaudeSession.buildArgs] byte-for-byte (the binary parses them).
 * - [mcpConfigJson] / [resolveStdioParams] / [findMcpServerLib] touch the running IDE (PathManager / PluginManager,
 *   public API only — no `@ApiStatus.Internal` descriptor lookups) to synthesize the stdio transport command; the
 *   JSON shape itself is delegated to the already-pure [McpConfigBuilder].
 */
object SessionLauncher {

    private val log = thisLogger()

    /** JetBrains' bundled MCP Server plugin — the one we drive over stdio when IDE tools are enabled. */
    private const val MCP_SERVER_PLUGIN_ID = "com.intellij.mcpServer"

    /**
     * Immutable snapshot of every launch-affecting session option. Mirrors the fields [ClaudeSession] reads inside
     * [buildArgs] / [mcpConfigJson]; the session builds one of these from its volatile state before each (re)start.
     */
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
        // --- Advanced launch options (neutral = flag omitted) -------------------------------------
        val maxTurns: Int? = null,
        val maxBudgetUsd: Double? = null,
        val fallbackModel: String? = null,
        val addDirs: List<String> = emptyList(),
        val betas: String? = null,
        val strictMcpConfig: Boolean = false,
    )

    /**
     * The mode the binary actually runs in. `acceptEdits`/`bypassPermissions` are enforced host-side by the
     * [dev.lain.claudejb.permission.PermissionBroker]: we keep the binary in `default` so it still routes every edit
     * through `--permission-prompt-tool stdio`. That round-trip is what lets us open the native diff in the IDE before
     * the binary writes — newer binaries auto-approve edits internally in acceptEdits and never prompt, so the diff
     * would otherwise never appear. `default`/`plan` pass through unchanged.
     */
    fun binaryPermissionMode(mode: String): String =
        if (mode == "acceptEdits" || mode == "bypassPermissions") "default" else mode

    /**
     * Builds the CLI argument vector. PURE: the [mcpConfig] JSON (computed by [mcpConfigJson], which needs the IDE) is
     * passed in so this stays unit-testable. Argument order is load-bearing and matches the historical
     * [ClaudeSession.buildArgs].
     */
    fun buildArgs(opts: LaunchOptions, resume: Boolean, mcpConfig: String?): List<String> {
        val args = mutableListOf(
            "--print",
            "--output-format", "stream-json",
            "--input-format", "stream-json",
            "--verbose",
            "--permission-prompt-tool", "stdio",
            "--permission-mode", binaryPermissionMode(opts.permissionMode),
        )
        if (opts.includePartialMessages) args += "--include-partial-messages"
        if (opts.settingSources.isNotBlank()) args += listOf("--setting-sources", opts.settingSources)
        opts.model?.let { args += listOf("--model", it) }
        opts.effort?.let { args += listOf("--effort", it) }
        // Extended thinking (adaptive): a launch flag on current models. `--thinking-display summarized` is what
        // actually streams the reasoning blocks; without it no "Thought process" appears. Any non-null budget = on.
        if (opts.thinkingTokens != null) args += listOf("--thinking", "adaptive", "--thinking-display", "summarized")
        opts.allowedTools.trim().ifBlank { null }?.let { args += listOf("--allowedTools", it) }
        opts.disallowedTools.trim().ifBlank { null }?.let { args += listOf("--disallowedTools", it) }
        // Advanced launch options: each emitted only when it carries a value (neutral default → omitted).
        opts.maxTurns?.let { args += listOf("--max-turns", it.toString()) }
        opts.maxBudgetUsd?.let { args += listOf("--max-budget-usd", it.toString()) }
        opts.fallbackModel?.trim()?.ifBlank { null }?.let { args += listOf("--fallback-model", it) }
        for (dir in opts.addDirs) dir.trim().ifBlank { null }?.let { args += listOf("--add-dir", it) }
        opts.betas?.trim()?.ifBlank { null }?.let { args += listOf("--betas", it) }
        if (opts.strictMcpConfig) args += "--strict-mcp-config"
        mcpConfig?.let { args += listOf("--mcp-config", it) }
        if (resume) opts.sessionId?.let { args += listOf("--resume", it) }
        return args
    }

    /**
     * Builds `{"mcpServers": …}` for `--mcp-config` by delegating to the pure [McpConfigBuilder] (testable without
     * the IDE). The only IDE-coupled bit — resolving the stdio command from the running IDE's paths — is computed
     * here as [resolveStdioParams] and handed in; everything else is plain data. The wire format is unchanged.
     */
    fun mcpConfigJson(opts: LaunchOptions): String? =
        McpConfigBuilder.mcpConfigJson(
            ideMcpEnabled = opts.ideMcpEnabled,
            transport = opts.ideMcpTransport,
            port = opts.ideMcpPort,
            customMcpServers = opts.customMcpServers,
            stdioParams = if (opts.ideMcpEnabled && opts.ideMcpTransport == "stdio") resolveStdioParams(opts) else null,
            onCustomParseError = { log.debug("Failed to parse custom MCP servers JSON", it) },
        )

    /**
     * Resolves the IDE-dependent inputs for the stdio transport: the JBR java, the bundled MCP Server plugin's lib
     * dir, the platform lib dir and the port. Returns null if the plugin can't be located (→ stdio isn't registered).
     * Located WITHOUT any descriptor-lookup API (every variant — `PluginManager.findEnabledPlugin`,
     * `PluginManager.getPlugin`, `PluginManagerCore.getPlugin` — is now `@ApiStatus.Internal` and the
     * Marketplace verifier rejects them). Instead: the public [PluginManager.isPluginInstalled] gates the
     * call, and the lib dir is resolved against the public [PathManager.getPluginsPath] /
     * [PathManager.getPreInstalledPluginsPath] roots using the plugin folder name (a bundled plugin's
     * directory matches the camelCase tail of its id — for `com.intellij.mcpServer` that's `mcpServer`).
     */
    fun resolveStdioParams(opts: LaunchOptions): McpConfigBuilder.StdioParams? {
        if (!InstalledPlugins.isEnabled(MCP_SERVER_PLUGIN_ID)) return null
        val pluginLib = findMcpServerLib() ?: return null
        val javaBin = File(File(System.getProperty("java.home"), "bin"), if (SystemInfo.isWindows) "java.exe" else "java")
        return McpConfigBuilder.StdioParams(javaBin, pluginLib, PathManager.getLibPath(), opts.ideMcpPort)
    }

    /** Searches the standard plugin roots for the MCP server's `lib/` directory; returns null if none found. */
    fun findMcpServerLib(): File? {
        val names = listOf("mcpServer", "mcp-server", "MCP Server")
        val roots = listOfNotNull(
            runCatching { java.nio.file.Paths.get(PathManager.getPluginsPath()) }.getOrNull(),
            runCatching { java.nio.file.Paths.get(PathManager.getPreInstalledPluginsPath()) }.getOrNull(),
        )
        for (root in roots) for (name in names) {
            val lib = root.resolve(name).resolve("lib")
            if (java.nio.file.Files.isDirectory(lib)) return lib.toFile()
        }
        return null
    }
}
