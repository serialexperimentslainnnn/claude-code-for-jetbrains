package dev.lain.claudejb.model.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ToolException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class ToolArgs(val json: JsonObject, val toolUseId: String? = null) {

    fun string(key: String): String = optionalString(key) ?: throw ToolException("missing argument: $key")

    fun strings(key: String): List<String> {
        val value = json[key] ?: return emptyList()
        val items = (value as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        if (items == null || items.any { it == null }) throw ToolException("argument $key must be an array of strings")
        return items.filterNotNull()
    }

    fun optionalString(key: String): String? {
        val value = json[key] ?: return null
        val primitive = value as? JsonPrimitive ?: throw ToolException("argument $key must be a string")
        return primitive.content
    }

    fun int(key: String, default: Int): Int {
        val raw = optionalString(key) ?: return default
        return raw.toIntOrNull() ?: throw ToolException("argument $key must be an integer")
    }

    fun boolean(key: String, default: Boolean): Boolean = optionalBoolean(key) ?: default

    fun optionalBoolean(key: String): Boolean? = when (optionalString(key)) {
        null -> null
        "true" -> true
        "false" -> false
        else -> throw ToolException("argument $key must be a boolean")
    }
}
