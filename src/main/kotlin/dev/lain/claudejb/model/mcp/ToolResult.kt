package dev.lain.claudejb.model.mcp

import dev.lain.claudejb.model.mcp.toon.Toon
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ToolResult(val text: String, val isError: Boolean = false) {

    companion object {

        fun toon(value: JsonElement): ToolResult = ToolResult(Toon.encode(value))

        fun error(message: String): ToolResult = ToolResult(Toon.encode(buildJsonObject { put("error", message) }), isError = true)
    }
}

class OutputBudget(val maxChars: Int = DEFAULT_MAX_CHARS) {

    fun fit(text: String): String {
        if (text.length <= maxChars) return text
        val room = maxChars - NOTICE_ROOM
        val cut = text.lastIndexOf('\n', room).takeIf { it > 0 } ?: room
        return text.substring(0, cut) + "\n# truncated: $cut of ${text.length} chars shown; narrow the request"
    }

    companion object {
        const val DEFAULT_MAX_CHARS = 16_000
        private const val NOTICE_ROOM = 80
    }
}
