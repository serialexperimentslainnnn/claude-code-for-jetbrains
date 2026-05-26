package dev.lain.claudejb.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A single parsed line of the binary's stdout, normalized into the handful of cases the plugin acts on.
 * Anything unrecognized becomes [Other] so the reader loop never throws.
 */
sealed interface ClaudeEvent {
    /** system/init — capture [SystemInit.sessionId] for --resume and the initial slash command names. */
    data class Init(val info: SystemInit) : ClaudeEvent

    /** A finalized text block from a full assistant message. */
    data class AssistantText(val text: String, val parentToolUseId: String?) : ClaudeEvent

    /** A finalized thinking block. */
    data class AssistantThinking(val text: String) : ClaudeEvent

    /** A tool_use block: the agent wants to run [name] with [input]. [parentToolUseId] is set when the call
     *  comes from inside a subagent (Task), so the UI can nest it under that Agent. */
    data class ToolUse(val id: String, val name: String, val input: JsonObject, val parentToolUseId: String?) : ClaudeEvent

    /** The result of a tool execution, emitted by the binary as a user/tool_result block. [parentToolUseId]
     *  is set for subagent results so the output nests under its Agent. */
    data class ToolResult(val toolUseId: String, val content: String, val isError: Boolean, val parentToolUseId: String?) : ClaudeEvent

    /** Incremental text delta from --include-partial-messages (live streaming preview). */
    data class TextDelta(val text: String) : ClaudeEvent

    /** Incremental thinking delta. */
    data class ThinkingDelta(val text: String) : ClaudeEvent

    /** Live output-token count for the message being streamed (from message_delta usage). */
    data class LiveUsage(val outputTokens: Int) : ClaudeEvent

    /** Boundary between assistant messages within a turn (a new message starts streaming). */
    data object MessageStart : ClaudeEvent

    /** End of a turn. */
    data class Result(val result: ResultMessage) : ClaudeEvent

    /** Output of a CLI-local slash command (e.g. /cost, /clear) that did not go to the model. */
    data class LocalCommandOutput(val content: String) : ClaudeEvent

    /** A transient status from the binary (e.g. compaction running/finished) worth surfacing as a notice. */
    data class StatusNotice(val text: String) : ClaudeEvent

    /** A `can_use_tool` permission request the host must answer. */
    data class PermissionRequest(val requestId: String, val request: CanUseToolRequest) : ClaudeEvent

    /** A binary->host control request we don't implement; must still be answered (error) so the binary doesn't hang. */
    data class UnsupportedControlRequest(val requestId: String, val subtype: String?) : ClaudeEvent

    /** `rate_limit_event` — subscription quota usage update (drives the quota bar). */
    data class RateLimit(val info: RateLimitInfo) : ClaudeEvent

    /** Reply from the binary to a host-initiated control_request, correlated by [requestId]. */
    data class ControlResult(
        val requestId: String,
        val success: Boolean,
        val payload: JsonObject?,
        val error: String?,
    ) : ClaudeEvent

    /** Any other (ignored) message; kept for logging/debugging. */
    data class Other(val type: String, val subtype: String?, val raw: JsonObject) : ClaudeEvent
}

/** Stateless decoder from one NDJSON line to a list of [ClaudeEvent]s (an assistant message fans out per block). */
object ProtocolParser {

    fun parse(line: String): List<ClaudeEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()
        val root = runCatching { ClaudeJson.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject
            ?: return listOf(ClaudeEvent.Other("?", null, JsonObject(emptyMap())))
        val type = root.str("type") ?: return listOf(ClaudeEvent.Other("?", null, root))

        return when (type) {
            "system" -> parseSystem(root)
            "assistant" -> parseAssistant(root)
            "user" -> parseUser(root)
            "stream_event" -> parseStreamEvent(root)
            "result" -> listOf(ClaudeEvent.Result(ClaudeJson.decodeFromJsonElement(ResultMessage.serializer(), root)))
            "control_request" -> parseControlRequest(root)
            "control_response" -> parseControlResponse(root)
            "rate_limit_event" -> parseRateLimit(root)
            "keep_alive" -> emptyList()
            else -> listOf(ClaudeEvent.Other(type, root.str("subtype"), root))
        }
    }

    private fun parseSystem(root: JsonObject): List<ClaudeEvent> = when (root.str("subtype")) {
        "init" -> listOf(ClaudeEvent.Init(ClaudeJson.decodeFromJsonElement(SystemInit.serializer(), root)))
        "local_command_output" -> listOf(ClaudeEvent.LocalCommandOutput(root.str("content").orEmpty()))
        "status" -> parseStatus(root)
        "compact_boundary" -> parseCompactBoundary(root)
        else -> listOf(ClaudeEvent.Other("system", root.str("subtype"), root))
    }

    /**
     * `system/status` — the binary's transient activity. We surface only compaction (start/result); the
     * `requesting` status and the idle reset (`status:null` without a compact result) are already implied by
     * the turn spinner, so they're dropped to avoid noise.
     */
    private fun parseStatus(root: JsonObject): List<ClaudeEvent> {
        root.str("compact_result")?.let { result ->
            val text = if (result == "success") "✓ Conversation compacted"
            else "Compaction failed" + (root.str("compact_error")?.let { ": $it" } ?: "")
            return listOf(ClaudeEvent.StatusNotice(text))
        }
        return when (root.str("status")) {
            "compacting" -> listOf(ClaudeEvent.StatusNotice("Compacting conversation…"))
            else -> emptyList()
        }
    }

    /** `system/compact_boundary` — a one-line summary of how much context the compaction reclaimed. */
    private fun parseCompactBoundary(root: JsonObject): List<ClaudeEvent> {
        val meta = root["compact_metadata"] as? JsonObject ?: return emptyList()
        val trigger = meta.str("trigger") ?: "manual"
        val pre = meta.intField("pre_tokens")
        val post = meta.intField("post_tokens")
        val ms = meta.intField("duration_ms")
        val tokens = if (pre != null && post != null) "${tokens(pre)} → ${tokens(post)} tokens" else "context reduced"
        val took = ms?.let { " · ${it / 1000}s" } ?: ""
        return listOf(ClaudeEvent.StatusNotice("Context compacted ($trigger): $tokens$took"))
    }

    private fun tokens(n: Int): String =
        if (n >= 1000) String.format("%.1fk", n / 1000.0) else n.toString()

    private fun parseAssistant(root: JsonObject): List<ClaudeEvent> {
        val parentToolUseId = root.str("parent_tool_use_id")
        val inner = (root["message"] as? JsonObject)
            ?.let { ClaudeJson.decodeFromJsonElement(AssistantInner.serializer(), it) }
            ?: return listOf(ClaudeEvent.Other("assistant", null, root))
        val out = ArrayList<ClaudeEvent>(inner.content.size)
        for (block in inner.content) {
            when (block.str("type")) {
                "text" -> block.str("text")?.takeIf { it.isNotEmpty() }
                    ?.let { out += ClaudeEvent.AssistantText(it, parentToolUseId) }
                "thinking" -> block.str("thinking")?.takeIf { it.isNotEmpty() }
                    ?.let { out += ClaudeEvent.AssistantThinking(it) }
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

    private fun parseStreamEvent(root: JsonObject): List<ClaudeEvent> {
        val event = root["event"] as? JsonObject ?: return emptyList()
        return when (event.str("type")) {
            "message_start" -> listOf(ClaudeEvent.MessageStart)
            "message_delta" -> {
                val out = (event["usage"] as? JsonObject)?.intField("output_tokens")
                if (out != null) listOf(ClaudeEvent.LiveUsage(out)) else emptyList()
            }
            "content_block_delta" -> {
                val delta = event["delta"] as? JsonObject ?: return emptyList()
                when (delta.str("type")) {
                    "text_delta" -> delta.str("text")?.let { listOf(ClaudeEvent.TextDelta(it)) } ?: emptyList()
                    "thinking_delta" -> delta.str("thinking")?.let { listOf(ClaudeEvent.ThinkingDelta(it)) } ?: emptyList()
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }
    }

    private fun parseUser(root: JsonObject): List<ClaudeEvent> {
        val message = root["message"] as? JsonObject ?: return emptyList()
        val content = message["content"] as? JsonArray ?: return emptyList()
        val parentToolUseId = root.str("parent_tool_use_id")
        return content.filterIsInstance<JsonObject>().mapNotNull { block ->
            if (block.str("type") != "tool_result") return@mapNotNull null
            val toolUseId = block.str("tool_use_id") ?: return@mapNotNull null
            val isError = (block["is_error"] as? JsonPrimitive)?.booleanOrNull ?: false
            val text = when (val c = block["content"]) {
                is JsonPrimitive -> c.contentOrNull.orEmpty()
                is JsonArray -> c.filterIsInstance<JsonObject>().mapNotNull { it.str("text") }.joinToString("\n")
                else -> ""
            }
            ClaudeEvent.ToolResult(toolUseId, text, isError, parentToolUseId)
        }
    }

    private fun parseControlRequest(root: JsonObject): List<ClaudeEvent> {
        val requestId = root.str("request_id") ?: return emptyList()
        val request = root["request"] as? JsonObject ?: return emptyList()
        return when (request.str("subtype")) {
            "can_use_tool" -> listOf(
                ClaudeEvent.PermissionRequest(
                    requestId,
                    ClaudeJson.decodeFromJsonElement(CanUseToolRequest.serializer(), request),
                )
            )
            // Other binary->host requests (mcp_message, hook_callback, elicitation, request_user_dialog…)
            // are not handled in the MVP; the session replies with an error so the binary is not left waiting.
            else -> listOf(ClaudeEvent.UnsupportedControlRequest(requestId, request.str("subtype")))
        }
    }

    private fun parseRateLimit(root: JsonObject): List<ClaudeEvent> {
        val info = root["rate_limit_info"] as? JsonObject ?: return emptyList()
        return runCatching {
            listOf(ClaudeEvent.RateLimit(ClaudeJson.decodeFromJsonElement(RateLimitInfo.serializer(), info)))
        }.getOrDefault(emptyList())
    }

    private fun parseControlResponse(root: JsonObject): List<ClaudeEvent> {
        val response = root["response"] as? JsonObject ?: return emptyList()
        val requestId = response.str("request_id") ?: return emptyList()
        val success = response.str("subtype") == "success"
        return listOf(
            ClaudeEvent.ControlResult(
                requestId = requestId,
                success = success,
                payload = response["response"] as? JsonObject,
                error = response.str("error"),
            )
        )
    }
}

/** Null-safe string accessor for a [JsonObject] field that is a JSON primitive. */
internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

/** Null-safe int accessor for a [JsonObject] field that is a JSON primitive. */
internal fun JsonObject.intField(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull
