package dev.lain.claudejb.model.protocol.parse

import dev.lain.claudejb.model.protocol.ClaudeEvent
import dev.lain.claudejb.model.protocol.ClaudeJson
import dev.lain.claudejb.model.protocol.intField
import dev.lain.claudejb.model.protocol.models.ApiRetryInfo
import dev.lain.claudejb.model.protocol.models.AuthStatusInfo
import dev.lain.claudejb.model.protocol.models.BackgroundTasksChangedInfo
import dev.lain.claudejb.model.protocol.models.CommandsChangedInfo
import dev.lain.claudejb.model.protocol.models.ControlRequestProgressInfo
import dev.lain.claudejb.model.protocol.models.FilesPersistedInfo
import dev.lain.claudejb.model.protocol.models.HookProgressInfo
import dev.lain.claudejb.model.protocol.models.HookResponseInfo
import dev.lain.claudejb.model.protocol.models.HookStartedInfo
import dev.lain.claudejb.model.protocol.models.InformationalInfo
import dev.lain.claudejb.model.protocol.models.MemoryRecallInfo
import dev.lain.claudejb.model.protocol.models.MirrorErrorInfo
import dev.lain.claudejb.model.protocol.models.ModelRefusalFallbackInfo
import dev.lain.claudejb.model.protocol.models.ModelRefusalNoFallbackInfo
import dev.lain.claudejb.model.protocol.models.NotificationInfo
import dev.lain.claudejb.model.protocol.models.PermissionDeniedInfo
import dev.lain.claudejb.model.protocol.models.PluginInstallInfo
import dev.lain.claudejb.model.protocol.models.PromptSuggestionInfo
import dev.lain.claudejb.model.protocol.models.ResultMessage
import dev.lain.claudejb.model.protocol.models.SessionStateInfo
import dev.lain.claudejb.model.protocol.models.SystemInit
import dev.lain.claudejb.model.protocol.models.TaskNotificationInfo
import dev.lain.claudejb.model.protocol.models.TaskProgressInfo
import dev.lain.claudejb.model.protocol.models.TaskStartedInfo
import dev.lain.claudejb.model.protocol.models.TaskUpdatedInfo
import dev.lain.claudejb.model.protocol.models.ThinkingTokensInfo
import dev.lain.claudejb.model.protocol.models.ToolProgressInfo
import dev.lain.claudejb.model.protocol.models.ToolUseSummaryInfo
import dev.lain.claudejb.model.protocol.models.WorkerShuttingDownInfo
import dev.lain.claudejb.model.protocol.str
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

object ProtocolParser {

    private val TOP_LEVEL_DECODERS: Map<String, (JsonObject) -> List<ClaudeEvent>> = buildMap {
        fun <T> typed(type: String, serializer: KSerializer<T>, wrap: (T) -> ClaudeEvent) {
            put(type) { root -> decode(root, serializer, wrap, type) }
        }
        put("system", ::parseSystem)
        put("assistant", MessageParsers::parseAssistant)
        put("user", MessageParsers::parseUser)
        put("stream_event", MessageParsers::parseStreamEvent)
        put("control_request", ControlParsers::parseControlRequest)
        put("control_response", ControlParsers::parseControlResponse)
        put("control_cancel_request", ControlParsers::parseControlCancel)
        put("rate_limit_event", ControlParsers::parseRateLimit)
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
            ?: return listOf(ClaudeEvent.Other("?", null, JsonObject(emptyMap()), "malformed JSON (${trimmed.length} chars)"))
        val type = root.str("type") ?: return listOf(ClaudeEvent.Other("?", null, root, "no type field"))

        val decoder = TOP_LEVEL_DECODERS[type]
            ?: return listOf(ClaudeEvent.Other(type, root.str("subtype"), root))
        return decoder(root)
    }

    private val SYSTEM_DECODERS: Map<String, (JsonObject) -> List<ClaudeEvent>> = buildMap {
        fun <T> typed(subtype: String, serializer: KSerializer<T>, wrap: (T) -> ClaudeEvent) {
            put(subtype) { root -> decode(root, serializer, wrap, "system") }
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
        serializer: KSerializer<T>,
        wrap: (T) -> ClaudeEvent,
        fallbackType: String,
    ): List<ClaudeEvent> = runCatching {
        listOf(wrap(ClaudeJson.decodeFromJsonElement(serializer, root)))
    }.getOrElse { listOf(ClaudeEvent.Other(fallbackType, root.str("subtype"), root, it.toString())) }

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
}
