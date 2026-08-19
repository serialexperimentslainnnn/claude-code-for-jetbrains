package dev.lain.claudejb.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

object ProtocolParser {

    private val TOP_LEVEL_DECODERS: Map<String, (JsonObject) -> List<ClaudeEvent>> = buildMap {
        fun <T> typed(type: String, serializer: kotlinx.serialization.KSerializer<T>, wrap: (T) -> ClaudeEvent) {
            put(type) { root -> decode(root, serializer, wrap, type, root) }
        }
        put("system", ::parseSystem)
        put("assistant", ::parseAssistant)
        put("user", ::parseUser)
        put("stream_event", ::parseStreamEvent)
        put("control_request", ::parseControlRequest)
        put("control_response", ::parseControlResponse)
        put("rate_limit_event", ::parseRateLimit)
        put("keep_alive") { emptyList() }
        typed("result", ResultMessage.serializer(), ClaudeEvent::Result)
        typed("auth_status", AuthStatusInfo.serializer(), ClaudeEvent::AuthStatus)
        typed("tool_progress", ToolProgressInfo.serializer(), ClaudeEvent::ToolProgress)
        typed("tool_use_summary", ToolUseSummaryInfo.serializer(), ClaudeEvent::ToolUseSummary)
        typed("prompt_suggestion", PromptSuggestionInfo.serializer(), ClaudeEvent::PromptSuggestion)
    }

    fun parse(line: String): List<ClaudeEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()
        val root = runCatching { ClaudeJson.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject
            ?: return listOf(ClaudeEvent.Other("?", null, JsonObject(emptyMap())))
        val type = root.str("type") ?: return listOf(ClaudeEvent.Other("?", null, root))

        val decoder = TOP_LEVEL_DECODERS[type]
            ?: return listOf(ClaudeEvent.Other(type, root.str("subtype"), root))
        return decoder(root)
    }

    private val SYSTEM_DECODERS: Map<String, (JsonObject) -> List<ClaudeEvent>> = buildMap {
        fun <T> typed(subtype: String, serializer: kotlinx.serialization.KSerializer<T>, wrap: (T) -> ClaudeEvent) {
            put(subtype) { root -> decode(root, serializer, wrap, "system", root) }
        }
        typed("init", SystemInit.serializer(), ClaudeEvent::Init)
        typed("task_started", TaskStartedInfo.serializer(), ClaudeEvent::TaskStarted)
        typed("task_progress", TaskProgressInfo.serializer(), ClaudeEvent::TaskProgress)
        typed("task_updated", TaskUpdatedInfo.serializer(), ClaudeEvent::TaskUpdated)
        typed("task_notification", TaskNotificationInfo.serializer(), ClaudeEvent::TaskNotification)
        typed("thinking_tokens", ThinkingTokensInfo.serializer(), ClaudeEvent::ThinkingTokens)
        typed("notification", NotificationInfo.serializer(), ClaudeEvent::Notification)
        typed("permission_denied", PermissionDeniedInfo.serializer(), ClaudeEvent::PermissionDenied)
        typed("session_state_changed", SessionStateInfo.serializer(), ClaudeEvent::SessionStateChanged)
        typed("api_retry", ApiRetryInfo.serializer(), ClaudeEvent::ApiRetry)
        typed("commands_changed", CommandsChangedInfo.serializer(), ClaudeEvent::CommandsChanged)
        typed("memory_recall", MemoryRecallInfo.serializer(), ClaudeEvent::MemoryRecall)
        typed("files_persisted", FilesPersistedInfo.serializer(), ClaudeEvent::FilesPersisted)
        typed("plugin_install", PluginInstallInfo.serializer(), ClaudeEvent::PluginInstall)
        typed("hook_started", HookStartedInfo.serializer(), ClaudeEvent::HookStarted)
        typed("hook_progress", HookProgressInfo.serializer(), ClaudeEvent::HookProgress)
        typed("hook_response", HookResponseInfo.serializer(), ClaudeEvent::HookResponse)
        typed("mirror_error", MirrorErrorInfo.serializer(), ClaudeEvent::MirrorError)
        typed("model_refusal_fallback", ModelRefusalFallbackInfo.serializer(), ClaudeEvent::ModelRefusalFallback)
        typed("informational", InformationalInfo.serializer(), ClaudeEvent::Informational)
        typed("model_refusal_no_fallback", ModelRefusalNoFallbackInfo.serializer(), ClaudeEvent::ModelRefusalNoFallback)
        typed("worker_shutting_down", WorkerShuttingDownInfo.serializer(), ClaudeEvent::WorkerShuttingDown)
        typed("background_tasks_changed", BackgroundTasksChangedInfo.serializer(), ClaudeEvent::BackgroundTasksChanged)
        typed("control_request_progress", ControlRequestProgressInfo.serializer(), ClaudeEvent::ControlRequestProgress)
        put("local_command_output") { listOf(ClaudeEvent.LocalCommandOutput(it.str("content").orEmpty())) }
        put("status", ::parseStatus)
        put("compact_boundary", ::parseCompactBoundary)
    }

    private fun parseSystem(root: JsonObject): List<ClaudeEvent> {
        val subtype = root.str("subtype")
        val decoder = SYSTEM_DECODERS[subtype] ?: return listOf(ClaudeEvent.Other("system", subtype, root))
        return decoder(root)
    }

    private fun <T> decode(
        root: JsonObject,
        serializer: kotlinx.serialization.KSerializer<T>,
        wrap: (T) -> ClaudeEvent,
        fallbackType: String,
        fallbackRaw: JsonObject,
    ): List<ClaudeEvent> = runCatching {
        listOf(wrap(ClaudeJson.decodeFromJsonElement(serializer, root)))
    }.getOrDefault(listOf(ClaudeEvent.Other(fallbackType, root.str("subtype"), fallbackRaw)))

    private fun parseStatus(root: JsonObject): List<ClaudeEvent> {
        root.str("compact_result")?.let { result ->
            val text = if (result == "success") {
                "✓ Conversation compacted"
            } else {
                "Compaction failed" + (root.str("compact_error")?.let { ": $it" } ?: "")
            }
            return listOf(ClaudeEvent.StatusNotice(text))
        }
        return when (root.str("status")) {
            "compacting" -> listOf(ClaudeEvent.StatusNotice("Compacting conversation…"))
            else -> emptyList()
        }
    }

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
        if (n >= 1000) String.format(java.util.Locale.ROOT, "%.1fk", n / 1000.0) else n.toString()

    private fun parseAssistant(root: JsonObject): List<ClaudeEvent> {
        val parentToolUseId = root.str("parent_tool_use_id")
        val inner = (root["message"] as? JsonObject)
            ?.let { runCatching { ClaudeJson.decodeFromJsonElement(AssistantInner.serializer(), it) }.getOrNull() }
            ?: return listOf(ClaudeEvent.Other("assistant", null, root))
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

    private fun parseStreamEvent(root: JsonObject): List<ClaudeEvent> {
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

    internal fun unwrapToolError(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith(TOOL_ERROR_OPEN) || !trimmed.endsWith(TOOL_ERROR_CLOSE)) return text
        return trimmed.removeSurrounding(TOOL_ERROR_OPEN, TOOL_ERROR_CLOSE).trim()
    }

    private const val TOOL_ERROR_OPEN = "<tool_use_error>"
    private const val TOOL_ERROR_CLOSE = "</tool_use_error>"

    private fun parseUser(root: JsonObject): List<ClaudeEvent> {
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

    internal fun parseToolOutput(obj: JsonObject?): ClaudeEvent.ToolOutputInfo? {
        if (obj == null) return null
        return ClaudeEvent.ToolOutputInfo(
            backgroundTaskId = obj.str("backgroundTaskId"),
            outputFile = obj.str("outputFile"),
            stdout = obj.str("stdout"),
            stderr = obj.str("stderr"),
        ).takeUnless { it.isEmpty() }
    }

    private fun parseControlRequest(root: JsonObject): List<ClaudeEvent> {
        val requestId = root.str("request_id") ?: return emptyList()
        val request = root["request"] as? JsonObject ?: return emptyList()
        return when (request.str("subtype")) {
            "can_use_tool" -> runCatching {
                listOf(ClaudeEvent.PermissionRequest(requestId, ClaudeJson.decodeFromJsonElement(CanUseToolRequest.serializer(), request)))
            }.getOrDefault(listOf(ClaudeEvent.UnsupportedControlRequest(requestId, "can_use_tool")))

            "hook_callback" -> listOf(ClaudeEvent.HookCallback(requestId, request))

            "request_user_dialog" -> listOf(
                ClaudeEvent.UserDialogRequest(
                    requestId,
                    request.str("dialog_kind"),
                    (request["payload"] as? JsonObject) ?: JsonObject(emptyMap()),
                    request.str("tool_use_id"),
                ),
            )

            "elicitation" -> runCatching {
                listOf(ClaudeEvent.Elicitation(requestId, ClaudeJson.decodeFromJsonElement(ElicitationRequest.serializer(), request)))
            }.getOrDefault(listOf(ClaudeEvent.UnsupportedControlRequest(requestId, "elicitation")))

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
            ),
        )
    }
}
