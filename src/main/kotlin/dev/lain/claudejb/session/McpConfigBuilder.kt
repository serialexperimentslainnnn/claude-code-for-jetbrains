package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.ClaudeJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * Pure (IDE-free) construction of the `--mcp-config` JSON, extracted out of [ClaudeSession] so the wire format
 * can be unit-tested without a running IDE. Every method takes its inputs as plain arguments instead of reading
 * session/platform state; the only IDE-coupled bit (resolving the stdio command from the running IDE's paths) is
 * resolved by the caller and passed in as a ready-made [StdioParams] (or null → the stdio transport is skipped).
 *
 * The output must stay byte-for-byte identical to what [ClaudeSession] used to emit: the binary parses it, so the
 * key order, the synthesized localhost endpoints and the empty `headers` object are all load-bearing.
 */
object McpConfigBuilder {

    /** Resolved IDE paths for the stdio transport (the only branch that needs the running IDE). */
    data class StdioParams(
        /** Absolute path of the JBR `java` executable (with `.exe` on Windows). */
        val javaBin: File,
        /** The bundled `mcpserver` plugin's `lib` directory. */
        val pluginLib: File,
        /** The platform `lib` directory ([com.intellij.openapi.application.PathManager.getLibPath]). */
        val platformLib: String,
        val port: Int,
    )

    /**
     * Builds `{"mcpServers": …}`, merging (when enabled) JetBrains' own server under the `jetbrains` key with the
     * user's custom servers. Returns null (skipping the flag) when there's nothing to register, so a launch is never
     * blocked by an absent/invalid config.
     */
    fun mcpConfigJson(
        ideMcpEnabled: Boolean,
        transport: String,
        port: Int,
        customMcpServers: String,
        stdioParams: StdioParams? = null,
        onCustomParseError: (Throwable) -> Unit = {},
    ): String? {
        val servers = buildJsonObject {
            if (ideMcpEnabled) jetbrainsMcpServer(transport, port, stdioParams)?.let { put("jetbrains", it) }
            customMcpServersObject(customMcpServers, onCustomParseError)?.forEach { (name, server) -> put(name, server) }
        }
        if (servers.isEmpty()) return null
        return buildJsonObject { put("mcpServers", servers) }.toString()
    }

    /** The JetBrains server object for the selected transport. stdio needs the pre-resolved [stdioParams]. */
    fun jetbrainsMcpServer(transport: String, port: Int, stdioParams: StdioParams?): JsonObject? = when (transport) {
        "stdio" -> stdioParams?.let { stdioMcpServer(it) }
        "streamable-http" -> httpMcpServer("streamable-http", "http://127.0.0.1:$port/stream")
        else -> httpMcpServer("sse", "http://127.0.0.1:$port/sse")
    }

    fun httpMcpServer(type: String, url: String): JsonObject = buildJsonObject {
        put("type", type)
        put("url", url)
        putJsonObject("headers") {}
    }

    /**
     * Synthesizes the stdio server config from pre-resolved IDE paths: the JBR java, the bundled "mcpserver" plugin
     * libs plus the platform lib dir (via the JVM classpath wildcard, so we don't hardcode jar names), and
     * IJ_MCP_SERVER_PORT. Returns null if the java binary or the plugin lib dir don't exist (→ stdio isn't registered).
     */
    fun stdioMcpServer(p: StdioParams): JsonObject? {
        if (!p.javaBin.exists() || !p.pluginLib.isDirectory) return null
        val sep = File.pathSeparator
        val classpath = "${p.pluginLib.absolutePath}${File.separator}*$sep${p.platformLib}${File.separator}*"
        return buildJsonObject {
            put("type", "stdio")
            put("command", p.javaBin.absolutePath)
            putJsonArray("args") {
                add("-classpath"); add(classpath); add("com.intellij.mcpserver.stdio.McpStdioRunnerKt")
            }
            putJsonObject("env") { put("IJ_MCP_SERVER_PORT", p.port.toString()) }
        }
    }

    /**
     * Parses [customMcpServers] as a `name → server` JSON object; null if blank or not a valid object. A parse
     * failure is reported via [onParseError] (the caller logs it) instead of being swallowed silently.
     */
    fun customMcpServersObject(customMcpServers: String, onParseError: (Throwable) -> Unit = {}): JsonObject? {
        val text = customMcpServers.trim().ifBlank { null } ?: return null
        return runCatching { ClaudeJson.parseToJsonElement(text) }
            .onFailure(onParseError)
            .getOrNull() as? JsonObject
    }
}
