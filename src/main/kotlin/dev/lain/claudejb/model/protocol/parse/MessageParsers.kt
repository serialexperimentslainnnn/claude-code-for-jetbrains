package dev.lain.claudejb.model.protocol.parse

import dev.lain.claudejb.model.protocol.ClaudeEvent
import dev.lain.claudejb.model.protocol.ClaudeJson
import dev.lain.claudejb.model.protocol.intField
import dev.lain.claudejb.model.protocol.models.AssistantInner
import dev.lain.claudejb.model.protocol.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal object MessageParsers {

    private const val TOOL_ERROR_OPEN = "<tool_use_error>"
    private const val TOOL_ERROR_CLOSE = "</tool_use_error>"

    fun parseAssistant(root: JsonObject): List<ClaudeEvent> {
        val parentToolUseId = root.str("parent_tool_use_id")
        val inner = (root["message"] as? JsonObject)
            ?.let { runCatching { ClaudeJson.decodeFromJsonElement(AssistantInner.serializer(), it) }.getOrNull() }
            ?: return listOf(ClaudeEvent.Other("assistant", null, root, "the message did not decode"))
        val out = ArrayList<ClaudeEvent>(inner.content.size)
        for (block in inner.content) {
            when (block.str("type")) {
                "text" -> block.str("text")?.takeIf { it.isNotEmpty() }
                    ?.let { out += ClaudeEvent.AssistantText(it, parentToolUseId) }

                "thinking" -> block.str("thinking")?.takeIf { it.isNotEmpty() }
                    ?.let { out += ClaudeEvent.AssistantThinking(it, parentToolUseId) }

                "tool_use" -> out += ClaudeEvent.ToolUse(
                    id = block.str("id").orEmpty(),
                    name = block.str("name").orEmpty(),
                    input = (block["input"] as? JsonObject) ?: JsonObject(emptyMap()),
                    parentToolUseId = parentToolUseId,
                )
            }
        }
        return out
    }

    fun parseStreamEvent(root: JsonObject): List<ClaudeEvent> {
        val event = root["event"] as? JsonObject ?: return emptyList()
        val parentToolUseId = root.str("parent_tool_use_id")
        return when (event.str("type")) {
            "message_start" -> {
                val u = (event["message"] as? JsonObject)?.get("usage") as? JsonObject
                if (u != null) listOf(ClaudeEvent.MessageStart, liveUsageFrom(u)) else listOf(ClaudeEvent.MessageStart)
            }

            "message_delta" -> {
                val u = event["usage"] as? JsonObject ?: return emptyList()
                listOf(liveUsageFrom(u))
            }

            "content_block_delta" -> parseContentBlockDelta(event, parentToolUseId)

            else -> emptyList()
        }
    }

    private fun parseContentBlockDelta(event: JsonObject, parentToolUseId: String?): List<ClaudeEvent> {
        val delta = event["delta"] as? JsonObject ?: return emptyList()
        return when (delta.str("type")) {
            "text_delta" -> delta.str("text")?.let { listOf(ClaudeEvent.TextDelta(it, parentToolUseId)) }.orEmpty()

            "thinking_delta" -> delta.str("thinking")?.takeIf { it.isNotEmpty() }
                ?.let { listOf(ClaudeEvent.ThinkingDelta(it, parentToolUseId)) }.orEmpty()

            else -> emptyList()
        }
    }

    private fun liveUsageFrom(u: JsonObject): ClaudeEvent.LiveUsage = ClaudeEvent.LiveUsage(
        inputTokens = u.intField("input_tokens") ?: 0,
        cacheCreationTokens = u.intField("cache_creation_input_tokens") ?: 0,
        cacheReadTokens = u.intField("cache_read_input_tokens") ?: 0,
        outputTokens = u.intField("output_tokens") ?: 0,
    )

    fun parseUser(root: JsonObject): List<ClaudeEvent> {
        val message = root["message"] as? JsonObject ?: return emptyList()
        val content = message["content"] as? JsonArray ?: return emptyList()
        val parentToolUseId = root.str("parent_tool_use_id")
        val blocks = content.filterIsInstance<JsonObject>().filter { it.str("type") == "tool_result" }
        val output = blocks.singleOrNull()?.let { parseToolOutput(root["tool_use_result"] as? JsonObject) }
        return blocks.mapNotNull { block ->
            val toolUseId = block.str("tool_use_id") ?: return@mapNotNull null
            val isError = (block["is_error"] as? JsonPrimitive)?.booleanOrNull ?: false
            val text = when (val c = block["content"]) {
                is JsonPrimitive -> c.contentOrNull.orEmpty()
                is JsonArray -> c.filterIsInstance<JsonObject>().mapNotNull { it.str("text") }.joinToString("\n")
                else -> ""
            }
            ClaudeEvent.ToolResult(toolUseId, unwrapToolError(text), isError, parentToolUseId, output)
        }
    }

    private fun unwrapToolError(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith(TOOL_ERROR_OPEN) || !trimmed.endsWith(TOOL_ERROR_CLOSE)) return text
        return trimmed.removeSurrounding(TOOL_ERROR_OPEN, TOOL_ERROR_CLOSE).trim()
    }

    fun parseToolOutput(obj: JsonObject?): ClaudeEvent.ToolOutputInfo? {
        if (obj == null) return null
        return ClaudeEvent.ToolOutputInfo(
            backgroundTaskId = obj.str("backgroundTaskId"),
            outputFile = obj.str("outputFile"),
            stdout = obj.str("stdout"),
            stderr = obj.str("stderr"),
        ).takeUnless { it.isEmpty() }
    }
}
