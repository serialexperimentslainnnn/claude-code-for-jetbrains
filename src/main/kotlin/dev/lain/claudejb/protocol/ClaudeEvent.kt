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

    /** Live token counters for the message being streamed (from `message_start.usage` / `message_delta.usage`).
     *  All four components are reported so the UI can show a faithful total — cache_creation_input_tokens
     *  alone is typically the largest line item and was previously discarded. */
    data class LiveUsage(
        val inputTokens: Int = 0,
        val cacheCreationTokens: Int = 0,
        val cacheReadTokens: Int = 0,
        val outputTokens: Int = 0,
    ) : ClaudeEvent

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

    /** A `hook_callback` control request: the binary fired a hook and blocks on the host's `HookJSONOutput` reply. */
    data class HookCallback(val requestId: String, val request: JsonObject) : ClaudeEvent

    /** `request_user_dialog` control request: the binary asks the host to render a tool-driven blocking dialog of
     *  an open-union [dialogKind] with an opaque per-kind [payload]. The host renders no custom kinds, so it is
     *  answered {behavior:"cancelled"} (the CLI then applies the dialog's own default). */
    data class UserDialogRequest(
        val requestId: String,
        val dialogKind: String?,
        val payload: JsonObject,
        val toolUseId: String?,
    ) : ClaudeEvent

    /** `elicitation` control request: an MCP server asks the user for input (a URL to complete, or a form). The
     *  host surfaces it as a non-modal card and answers with an ElicitResult (accept/decline/cancel). */
    data class Elicitation(val requestId: String, val request: ElicitationRequest) : ClaudeEvent

    /** A binary->host control request we don't implement; must still be answered (error) so the binary doesn't hang. */
    data class UnsupportedControlRequest(val requestId: String, val subtype: String?) : ClaudeEvent

    /** `rate_limit_event` — subscription quota usage update (drives the quota bar). */
    data class RateLimit(val info: RateLimitInfo) : ClaudeEvent

    // --- E1: additional system/* and stream events ---

    /** `system/task_started` — a subagent (Task) began. */
    data class TaskStarted(val info: TaskStartedInfo) : ClaudeEvent

    /** `system/task_progress` — periodic progress for a running subagent. */
    data class TaskProgress(val info: TaskProgressInfo) : ClaudeEvent

    /** `system/task_updated` — a wire-safe patch of changed subagent state. */
    data class TaskUpdated(val info: TaskUpdatedInfo) : ClaudeEvent

    /** `system/task_notification` — a subagent settled (completed/failed/stopped). */
    data class TaskNotification(val info: TaskNotificationInfo) : ClaudeEvent

    /** `tool_progress` — heartbeat for a long-running tool. */
    data class ToolProgress(val info: ToolProgressInfo) : ClaudeEvent

    /** `tool_use_summary` — a one-line summary spanning preceding tool calls. */
    data class ToolUseSummary(val info: ToolUseSummaryInfo) : ClaudeEvent

    /** `system/thinking_tokens` — live reasoning-token estimate. */
    data class ThinkingTokens(val info: ThinkingTokensInfo) : ClaudeEvent

    /** `system/notification` — loop-side text notification (key/priority/timeout). */
    data class Notification(val info: NotificationInfo) : ClaudeEvent

    /** `system/permission_denied` — a tool call auto-denied without a prompt. */
    data class PermissionDenied(val info: PermissionDeniedInfo) : ClaudeEvent

    /** `system/session_state_changed` — authoritative turn-state signal. */
    data class SessionStateChanged(val info: SessionStateInfo) : ClaudeEvent

    /** `auth_status` — auth backend (re)authenticating. */
    data class AuthStatus(val info: AuthStatusInfo) : ClaudeEvent

    /** `system/api_retry` — a retryable API failure that will be retried. */
    data class ApiRetry(val info: ApiRetryInfo) : ClaudeEvent

    /** `system/commands_changed` — full replacement slash-command list. */
    data class CommandsChanged(val info: CommandsChangedInfo) : ClaudeEvent

    /** `system/memory_recall` — memories surfaced into the turn. */
    data class MemoryRecall(val info: MemoryRecallInfo) : ClaudeEvent

    /** `system/files_persisted` — files uploaded to the Files API. */
    data class FilesPersisted(val info: FilesPersistedInfo) : ClaudeEvent

    /** `prompt_suggestion` — predicted next user prompt. */
    data class PromptSuggestion(val info: PromptSuggestionInfo) : ClaudeEvent

    /** `system/plugin_install` — headless plugin install progress. */
    data class PluginInstall(val info: PluginInstallInfo) : ClaudeEvent

    /** `system/hook_started` — a hook callback began. */
    data class HookStarted(val info: HookStartedInfo) : ClaudeEvent

    /** `system/hook_progress` — streaming output from a running hook. */
    data class HookProgress(val info: HookProgressInfo) : ClaudeEvent

    /** `system/hook_response` — a hook finished. */
    data class HookResponse(val info: HookResponseInfo) : ClaudeEvent

    /** `system/mirror_error` — the binary's transcript-mirror batch was dropped (data loss). */
    data class MirrorError(val info: MirrorErrorInfo) : ClaudeEvent

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
            // Top-level (non-system) types from sdk.d.ts that aren't message/stream frames.
            "auth_status" -> decode(root, AuthStatusInfo.serializer(), ClaudeEvent::AuthStatus, type, root)
            "tool_progress" -> decode(root, ToolProgressInfo.serializer(), ClaudeEvent::ToolProgress, type, root)
            "tool_use_summary" -> decode(root, ToolUseSummaryInfo.serializer(), ClaudeEvent::ToolUseSummary, type, root)
            "prompt_suggestion" -> decode(root, PromptSuggestionInfo.serializer(), ClaudeEvent::PromptSuggestion, type, root)
            else -> listOf(ClaudeEvent.Other(type, root.str("subtype"), root))
        }
    }

    private fun parseSystem(root: JsonObject): List<ClaudeEvent> = when (root.str("subtype")) {
        "init" -> listOf(ClaudeEvent.Init(ClaudeJson.decodeFromJsonElement(SystemInit.serializer(), root)))
        "local_command_output" -> listOf(ClaudeEvent.LocalCommandOutput(root.str("content").orEmpty()))
        "status" -> parseStatus(root)
        "compact_boundary" -> parseCompactBoundary(root)
        // E1: typed system subtypes that were previously dropped as Other.
        "task_started" -> decode(root, TaskStartedInfo.serializer(), ClaudeEvent::TaskStarted, "system", root)
        "task_progress" -> decode(root, TaskProgressInfo.serializer(), ClaudeEvent::TaskProgress, "system", root)
        "task_updated" -> decode(root, TaskUpdatedInfo.serializer(), ClaudeEvent::TaskUpdated, "system", root)
        "task_notification" -> decode(root, TaskNotificationInfo.serializer(), ClaudeEvent::TaskNotification, "system", root)
        "thinking_tokens" -> decode(root, ThinkingTokensInfo.serializer(), ClaudeEvent::ThinkingTokens, "system", root)
        "notification" -> decode(root, NotificationInfo.serializer(), ClaudeEvent::Notification, "system", root)
        "permission_denied" -> decode(root, PermissionDeniedInfo.serializer(), ClaudeEvent::PermissionDenied, "system", root)
        "session_state_changed" -> decode(root, SessionStateInfo.serializer(), ClaudeEvent::SessionStateChanged, "system", root)
        "api_retry" -> decode(root, ApiRetryInfo.serializer(), ClaudeEvent::ApiRetry, "system", root)
        "commands_changed" -> decode(root, CommandsChangedInfo.serializer(), ClaudeEvent::CommandsChanged, "system", root)
        "memory_recall" -> decode(root, MemoryRecallInfo.serializer(), ClaudeEvent::MemoryRecall, "system", root)
        "files_persisted" -> decode(root, FilesPersistedInfo.serializer(), ClaudeEvent::FilesPersisted, "system", root)
        "plugin_install" -> decode(root, PluginInstallInfo.serializer(), ClaudeEvent::PluginInstall, "system", root)
        "hook_started" -> decode(root, HookStartedInfo.serializer(), ClaudeEvent::HookStarted, "system", root)
        "hook_progress" -> decode(root, HookProgressInfo.serializer(), ClaudeEvent::HookProgress, "system", root)
        "hook_response" -> decode(root, HookResponseInfo.serializer(), ClaudeEvent::HookResponse, "system", root)
        "mirror_error" -> decode(root, MirrorErrorInfo.serializer(), ClaudeEvent::MirrorError, "system", root)
        else -> listOf(ClaudeEvent.Other("system", root.str("subtype"), root))
    }

    /**
     * Decodes [root] with [serializer] and wraps the result via [wrap]. Decoding is lenient and may still
     * fail on a hostile shape (e.g. a field typed as object arriving as a scalar) — never let that throw in
     * the reader loop; fall back to [ClaudeEvent.Other] so the line is logged, not lost.
     */
    private fun <T> decode(
        root: JsonObject,
        serializer: kotlinx.serialization.KSerializer<T>,
        wrap: (T) -> ClaudeEvent,
        fallbackType: String,
        fallbackRaw: JsonObject,
    ): List<ClaudeEvent> = runCatching {
        listOf(wrap(ClaudeJson.decodeFromJsonElement(serializer, root)))
    }.getOrDefault(listOf(ClaudeEvent.Other(fallbackType, root.str("subtype"), fallbackRaw)))

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
            "message_start" -> {
                // message_start carries usage too (input/cache values up-front, output usually 1). Surface it so
                // the live counter reflects the full per-message footprint immediately, not just output deltas.
                val u = (event["message"] as? JsonObject)?.get("usage") as? JsonObject
                if (u != null) listOf(ClaudeEvent.MessageStart, liveUsageFrom(u)) else listOf(ClaudeEvent.MessageStart)
            }
            "message_delta" -> {
                val u = event["usage"] as? JsonObject ?: return emptyList()
                listOf(liveUsageFrom(u))
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

    /** Extracts the four-component token usage from a `usage` JSON object (zero-filled when a key is absent). */
    private fun liveUsageFrom(u: JsonObject): ClaudeEvent.LiveUsage = ClaudeEvent.LiveUsage(
        inputTokens = u.intField("input_tokens") ?: 0,
        cacheCreationTokens = u.intField("cache_creation_input_tokens") ?: 0,
        cacheReadTokens = u.intField("cache_read_input_tokens") ?: 0,
        outputTokens = u.intField("output_tokens") ?: 0,
    )

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
            // hook_callback: the binary fired a hook and blocks on a HookJSONOutput reply (HookBroker owns the decision).
            "hook_callback" -> listOf(ClaudeEvent.HookCallback(requestId, request))
            // request_user_dialog: a tool-driven blocking dialog of an open-union kind — answered {behavior:"cancelled"}.
            "request_user_dialog" -> listOf(
                ClaudeEvent.UserDialogRequest(
                    requestId,
                    request.str("dialog_kind"),
                    (request["payload"] as? JsonObject) ?: JsonObject(emptyMap()),
                    request.str("tool_use_id"),
                )
            )
            // elicitation: an MCP server requests user input — surfaced as a card. A hostile frame still answers.
            "elicitation" -> runCatching {
                listOf(ClaudeEvent.Elicitation(requestId, ClaudeJson.decodeFromJsonElement(ElicitationRequest.serializer(), request)))
            }.getOrDefault(listOf(ClaudeEvent.UnsupportedControlRequest(requestId, "elicitation")))
            // Any other binary->host request (mcp_message, …) is unhandled; the session replies with an error so
            // the binary is not left waiting.
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
