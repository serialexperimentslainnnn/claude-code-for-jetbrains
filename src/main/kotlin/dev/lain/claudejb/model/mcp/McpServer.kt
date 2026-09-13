package dev.lain.claudejb.model.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class McpServer(
    private val name: String,
    private val version: String,
    private val meta: MetaTools,
) {

    suspend fun handle(message: JsonElement): JsonObject? = when (val parsed = JsonRpc.parse(message)) {
        is JsonRpc.Reply -> null
        is JsonRpc.Notification -> null
        is JsonRpc.Malformed -> JsonRpc.error(parsed.id, JsonRpc.INVALID_REQUEST, parsed.reason)
        is JsonRpc.Request -> request(parsed)
    }

    private suspend fun request(request: JsonRpc.Request): JsonObject {
        val requested = (JsonRpc.meta(request.params)[PROTOCOL_VERSION_KEY] as? JsonPrimitive)?.content
        if (requested != null && requested !in MODERN_VERSIONS) {
            return JsonRpc.error(request.id, JsonRpc.UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version", supported(requested))
        }
        return when (request.method) {
            "initialize" -> JsonRpc.result(request.id, initialize(request.params))
            "server/discover" -> JsonRpc.result(request.id, discover())
            "ping" -> JsonRpc.result(request.id, result {})
            "tools/list" -> JsonRpc.result(request.id, toolsList())
            "tools/call" -> toolsCall(request)
            else -> JsonRpc.error(request.id, JsonRpc.METHOD_NOT_FOUND, "Method not found: ${request.method}")
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.content
        return result {
            put("protocolVersion", if (requested in LEGACY_VERSIONS) requested else LEGACY_VERSIONS.last())
            put("capabilities", capabilities())
            put("serverInfo", serverInfo())
            put("instructions", INSTRUCTIONS)
        }
    }

    private fun discover(): JsonObject = result {
        put("supportedVersions", buildJsonArray { MODERN_VERSIONS.forEach { add(JsonPrimitive(it)) } })
        put("capabilities", capabilities())
        put("instructions", INSTRUCTIONS)
        put("ttlMs", DISCOVER_TTL_MILLIS)
        put("cacheScope", "public")
    }

    private fun toolsList(): JsonObject = result {
        put(
            "tools",
            buildJsonArray {
                for (spec in meta.specs) {
                    add(
                        buildJsonObject {
                            put("name", spec.name)
                            put("description", spec.description)
                            put("inputSchema", spec.inputSchema)
                        },
                    )
                }
            },
        )
        put("ttlMs", LIST_TTL_MILLIS)
        put("cacheScope", "private")
    }

    private suspend fun toolsCall(request: JsonRpc.Request): JsonObject {
        val name = (request.params["name"] as? JsonPrimitive)?.content
            ?: return JsonRpc.error(request.id, JsonRpc.INVALID_PARAMS, "tools/call needs a name")
        val arguments = request.params["arguments"]?.let { it as? JsonObject } ?: JsonObject(emptyMap())
        val outcome = try {
            meta.call(name, arguments, JsonRpc.meta(request.params))
                ?: return JsonRpc.error(request.id, JsonRpc.INVALID_PARAMS, "Unknown tool: $name")
        } catch (e: ToolException) {
            ToolResult.error(e.message ?: "invalid arguments")
        }
        return JsonRpc.result(
            request.id,
            result {
                put(
                    "content",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", outcome.text)
                            },
                        )
                    },
                )
                put("isError", outcome.isError)
            },
        )
    }

    private fun capabilities(): JsonObject = buildJsonObject {
        put("tools", buildJsonObject { put("listChanged", true) })
    }

    private fun serverInfo(): JsonObject = buildJsonObject {
        put("name", name)
        put("version", version)
    }

    private fun supported(requested: String): JsonObject = buildJsonObject {
        put("supported", buildJsonArray { MODERN_VERSIONS.forEach { add(JsonPrimitive(it)) } })
        put("requested", requested)
    }

    private fun result(body: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        put("resultType", "complete")
        body()
        put("_meta", buildJsonObject { put(SERVER_INFO_KEY, serverInfo()) })
    }

    companion object {
        const val PROTOCOL_VERSION_KEY = "io.modelcontextprotocol/protocolVersion"
        const val SERVER_INFO_KEY = "io.modelcontextprotocol/serverInfo"
        const val INSTRUCTIONS = "Call domains() first; every result is TOON."
        val MODERN_VERSIONS = listOf("2026-07-28")
        val LEGACY_VERSIONS = listOf("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")
        private const val DISCOVER_TTL_MILLIS = 3_600_000
        private const val LIST_TTL_MILLIS = 300_000
    }
}
