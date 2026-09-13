package dev.lain.claudejb.model.session.launch

import dev.lain.claudejb.model.protocol.ClaudeJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

object McpConfigBuilder {

    data class HelperParams(val javaBin: File, val lib: File)

    fun mcpConfigJson(
        customMcpServers: String,
        ownSockets: Map<IdeServer, String> = emptyMap(),
        helper: HelperParams? = null,
        onCustomParseError: (Throwable) -> Unit = {},
    ): String? {
        val servers = buildJsonObject {
            if (helper != null) ownSockets.forEach { (server, socket) -> put(server.mcpName, ownMcpServer(helper, socket)) }
            customMcpServersObject(customMcpServers, onCustomParseError)?.forEach { (name, server) -> put(name, server) }
        }
        if (servers.isEmpty()) return null
        return buildJsonObject { put("mcpServers", servers) }.toString()
    }

    fun ownMcpServer(helper: HelperParams, socket: String): JsonObject = buildJsonObject {
        put("type", "stdio")
        put("command", helper.javaBin.absolutePath)
        putJsonArray("args") {
            add("-cp")
            add(helper.lib.absolutePath + File.separator + "*")
            add(HELPER_MAIN)
            add(socket)
        }
    }

    const val HELPER_MAIN = "dev.lain.claudejb.mcp.StdioBridge"

    fun customMcpServersObject(customMcpServers: String, onParseError: (Throwable) -> Unit = {}): JsonObject? {
        val text = customMcpServers.trim().ifBlank { null } ?: return null
        return runCatching { ClaudeJson.parseToJsonElement(text) }
            .onFailure(onParseError)
            .getOrNull() as? JsonObject
    }
}
