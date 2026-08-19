package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.ClaudeJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

object McpConfigBuilder {

    data class StdioParams(
        val javaBin: File,
        val pluginLib: File,
        val platformLib: String,
        val port: Int,
    )

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

    fun stdioMcpServer(p: StdioParams): JsonObject? {
        if (!p.javaBin.exists() || !p.pluginLib.isDirectory) return null
        val sep = File.pathSeparator
        val classpath = "${p.pluginLib.absolutePath}${File.separator}*$sep${p.platformLib}${File.separator}*"
        return buildJsonObject {
            put("type", "stdio")
            put("command", p.javaBin.absolutePath)
            putJsonArray("args") {
                add("-classpath")
                add(classpath)
                add("com.intellij.mcpserver.stdio.McpStdioRunnerKt")
            }
            putJsonObject("env") { put("IJ_MCP_SERVER_PORT", p.port.toString()) }
        }
    }

    fun customMcpServersObject(customMcpServers: String, onParseError: (Throwable) -> Unit = {}): JsonObject? {
        val text = customMcpServers.trim().ifBlank { null } ?: return null
        return runCatching { ClaudeJson.parseToJsonElement(text) }
            .onFailure(onParseError)
            .getOrNull() as? JsonObject
    }
}
