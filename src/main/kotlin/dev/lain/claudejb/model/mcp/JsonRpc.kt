package dev.lain.claudejb.model.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object JsonRpc {

    const val VERSION = "2.0"
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    const val UNSUPPORTED_PROTOCOL_VERSION = -32022

    sealed interface Message

    class Request(val id: JsonElement, val method: String, val params: JsonObject) : Message

    class Notification(val method: String, val params: JsonObject) : Message

    class Reply(val id: JsonElement) : Message

    class Malformed(val id: JsonElement?, val reason: String) : Message

    fun parse(message: JsonElement): Message {
        val obj = message as? JsonObject ?: return Malformed(null, "a JSON-RPC message is an object")
        val id = obj["id"]?.takeUnless { it is JsonNull }
        val method = (obj["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val params = obj["params"]?.let { it as? JsonObject ?: return Malformed(id, "params must be an object") }
        return when {
            obj["jsonrpc"]?.jsonPrimitive?.content != VERSION -> Malformed(id, "jsonrpc must be \"$VERSION\"")
            method == null && id != null -> Reply(id)
            method == null -> Malformed(null, "a message carries a method or an id")
            id == null -> Notification(method, params ?: EMPTY)
            !validId(id) -> Malformed(null, "id must be a string or an integer")
            else -> Request(id, method, params ?: EMPTY)
        }
    }

    fun result(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", VERSION)
        put("id", id)
        put("result", result)
    }

    fun error(id: JsonElement?, code: Int, message: String, data: JsonObject? = null): JsonObject = buildJsonObject {
        put("jsonrpc", VERSION)
        put("id", id ?: JsonNull)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
                if (data != null) put("data", data)
            },
        )
    }

    fun meta(params: JsonObject): JsonObject = params["_meta"]?.let { it as? JsonObject } ?: EMPTY

    private fun validId(id: JsonElement): Boolean {
        val primitive = id as? JsonPrimitive ?: return false
        return primitive.isString || primitive.content.toLongOrNull() != null
    }

    private val EMPTY = JsonObject(emptyMap())
}
