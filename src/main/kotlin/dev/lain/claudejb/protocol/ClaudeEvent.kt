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

    // ── Grouping ──────────────────────────────────────────────────────────────────────────────────────────
    //
    // Every variant belongs to exactly one of the seven sub-interfaces below. They exist so the host can
    // dispatch in TWO levels — pick the group, then pick the variant within it — instead of one `when` with
    // 47 arms. That single `when` had a cyclomatic complexity of 111 and ran to 244 lines, which is not a
    // readability opinion: it is one function where every protocol concern in the plugin met.
    //
    // The split is deliberately expressed in the TYPE rather than as free functions, because a sealed
    // hierarchy keeps the compiler's exhaustiveness check at BOTH levels. Adding a variant to the protocol
    // without handling it is then a compile error, not a silently ignored frame — which is the entire reason
    // `checkDrift` exists, so losing it to satisfy a complexity rule would have been a bad trade.
    //
    // The groups are semantic, not cosmetic: they differ in what the host OWES the binary. A [Control] frame
    // must be answered or the binary hangs. A [Stream] delta is coalesced and may be dropped. A [Notice] is
    // fire-and-forget text. Handlers for different groups therefore have genuinely different contracts.

    /** Coalesced streaming deltas: buffered off-EDT and flushed together on the next non-stream event. */
    sealed interface Stream : ClaudeEvent

    /** The conversation itself — session start, assistant output, tool calls, end of turn. */
    sealed interface Conversation : ClaudeEvent

    /** Control-protocol traffic. Each request here MUST be answered, or the binary blocks on us forever. */
    sealed interface Control : ClaudeEvent

    /** Subagent and background-task telemetry. */
    sealed interface Task : ClaudeEvent

    /** Informational text the user sees as a transcript row. Fire-and-forget. */
    sealed interface Notice : ClaudeEvent

    /** Session state and metadata that drives UI chrome (quota, turn state, model, commands) rather than text. */
    sealed interface SessionSignal : ClaudeEvent

    /** The binary's own hook telemetry — narrated as one evolving row per hook. */
    sealed interface HookTelemetry : ClaudeEvent

    // ── Conversation ──────────────────────────────────────────────────────────────────────────────────────

    /** system/init — capture [SystemInit.sessionId] for --resume and the initial slash command names. */
    data class Init(val info: SystemInit) : Conversation

    /** A finalized text block from a full assistant message. */
    data class AssistantText(val text: String, val parentToolUseId: String?) : Conversation

    /** A finalized thinking block. */
    data class AssistantThinking(val text: String) : Conversation

    /** A tool_use block: the agent wants to run [name] with [input]. [parentToolUseId] is set when the call
     *  comes from inside a subagent (Task), so the UI can nest it under that Agent. */
    data class ToolUse(val id: String, val name: String, val input: JsonObject, val parentToolUseId: String?) : Conversation

    /** The result of a tool execution, emitted by the binary as a user/tool_result block. [parentToolUseId]
     *  is set for subagent results so the output nests under its Agent. */
    data class ToolResult(
        val toolUseId: String,
        val content: String,
        val isError: Boolean,
        val parentToolUseId: String?,
    ) : Conversation

    // ── Stream ────────────────────────────────────────────────────────────────────────────────────────────

    /** Incremental text delta from --include-partial-messages (live streaming preview). */
    data class TextDelta(val text: String) : Stream

    /** Incremental thinking delta. */
    data class ThinkingDelta(val text: String) : Stream

    /** Live token counters for the message being streamed (from `message_start.usage` / `message_delta.usage`).
     *  All four components are reported so the UI can show a faithful total — cache_creation_input_tokens
     *  alone is typically the largest line item and was previously discarded. */
    data class LiveUsage(
        val inputTokens: Int = 0,
        val cacheCreationTokens: Int = 0,
        val cacheReadTokens: Int = 0,
        val outputTokens: Int = 0,
    ) : Stream

    /** Boundary between assistant messages within a turn (a new message starts streaming). */
    data object MessageStart : Conversation

    /** End of a turn. */
    data class Result(val result: ResultMessage) : Conversation

    /** Output of a CLI-local slash command (e.g. /cost, /clear) that did not go to the model. */
    data class LocalCommandOutput(val content: String) : Conversation

    // ── Notice / Control ──────────────────────────────────────────────────────────────────────────────────

    /** A transient status from the binary (e.g. compaction running/finished) worth surfacing as a notice. */
    data class StatusNotice(val text: String) : Notice

    /** A `can_use_tool` permission request the host must answer. */
    data class PermissionRequest(val requestId: String, val request: CanUseToolRequest) : Control

    /** A `hook_callback` control request: the binary fired a hook and blocks on the host's `HookJSONOutput` reply. */
    data class HookCallback(val requestId: String, val request: JsonObject) : Control

    /** `request_user_dialog` control request: the binary asks the host to render a tool-driven blocking dialog of
     *  an open-union [dialogKind] with an opaque per-kind [payload]. The host renders no custom kinds, so it is
     *  answered {behavior:"cancelled"} (the CLI then applies the dialog's own default). */
    data class UserDialogRequest(
        val requestId: String,
        val dialogKind: String?,
        val payload: JsonObject,
        val toolUseId: String?,
    ) : Control

    /** `elicitation` control request: an MCP server asks the user for input (a URL to complete, or a form). The
     *  host surfaces it as a non-modal card and answers with an ElicitResult (accept/decline/cancel). */
    data class Elicitation(val requestId: String, val request: ElicitationRequest) : Control

    /** A binary->host control request we don't implement; must still be answered (error) so the binary doesn't hang. */
    data class UnsupportedControlRequest(val requestId: String, val subtype: String?) : Control

    /** Reply from the binary to a host-initiated control_request, correlated by [requestId]. */
    data class ControlResult(
        val requestId: String,
        val success: Boolean,
        val payload: JsonObject?,
        val error: String?,
    ) : Control

    // ── Task ──────────────────────────────────────────────────────────────────────────────────────────────

    /** `system/task_started` — a subagent (Task) began. */
    data class TaskStarted(val info: TaskStartedInfo) : Task

    /** `system/task_progress` — periodic progress for a running subagent. */
    data class TaskProgress(val info: TaskProgressInfo) : Task

    /** `system/task_updated` — a wire-safe patch of changed subagent state. */
    data class TaskUpdated(val info: TaskUpdatedInfo) : Task

    /** `system/task_notification` — a subagent settled (completed/failed/stopped). */
    data class TaskNotification(val info: TaskNotificationInfo) : Task

    /** `tool_progress` — heartbeat for a long-running tool. */
    data class ToolProgress(val info: ToolProgressInfo) : Task

    /** `tool_use_summary` — a one-line summary spanning preceding tool calls. */
    data class ToolUseSummary(val info: ToolUseSummaryInfo) : Task

    /** `system/background_tasks_changed` — the full live background-task set (level signal, REPLACE semantics). */
    data class BackgroundTasksChanged(val info: BackgroundTasksChangedInfo) : Task

    // ── SessionSignal ─────────────────────────────────────────────────────────────────────────────────────

    /** `rate_limit_event` — subscription quota usage update (drives the quota bar). */
    data class RateLimit(val info: RateLimitInfo) : SessionSignal

    /** `system/thinking_tokens` — live reasoning-token estimate. */
    data class ThinkingTokens(val info: ThinkingTokensInfo) : SessionSignal

    /** `system/session_state_changed` — authoritative turn-state signal. */
    data class SessionStateChanged(val info: SessionStateInfo) : SessionSignal

    /** `auth_status` — auth backend (re)authenticating. */
    data class AuthStatus(val info: AuthStatusInfo) : SessionSignal

    /** `system/api_retry` — a retryable API failure that will be retried. */
    data class ApiRetry(val info: ApiRetryInfo) : SessionSignal

    /** `system/commands_changed` — full replacement slash-command list. */
    data class CommandsChanged(val info: CommandsChangedInfo) : SessionSignal

    /** `prompt_suggestion` — predicted next user prompt. */
    data class PromptSuggestion(val info: PromptSuggestionInfo) : SessionSignal

    /** `system/control_request_progress` — progress for a host-originated control request (side_question). */
    data class ControlRequestProgress(val info: ControlRequestProgressInfo) : SessionSignal

    // ── HookTelemetry ─────────────────────────────────────────────────────────────────────────────────────

    /** `system/hook_started` — a hook callback began. */
    data class HookStarted(val info: HookStartedInfo) : HookTelemetry

    /** `system/hook_progress` — streaming output from a running hook. */
    data class HookProgress(val info: HookProgressInfo) : HookTelemetry

    /** `system/hook_response` — a hook finished. */
    data class HookResponse(val info: HookResponseInfo) : HookTelemetry

    // ── Notice ────────────────────────────────────────────────────────────────────────────────────────────

    /** `system/notification` — loop-side text notification (key/priority/timeout). */
    data class Notification(val info: NotificationInfo) : Notice

    /** `system/permission_denied` — a tool call auto-denied without a prompt. */
    data class PermissionDenied(val info: PermissionDeniedInfo) : Notice

    /** `system/memory_recall` — memories surfaced into the turn. */
    data class MemoryRecall(val info: MemoryRecallInfo) : Notice

    /** `system/files_persisted` — files uploaded to the Files API. */
    data class FilesPersisted(val info: FilesPersistedInfo) : Notice

    /** `system/plugin_install` — headless plugin install progress. */
    data class PluginInstall(val info: PluginInstallInfo) : Notice

    /** `system/mirror_error` — the binary's transcript-mirror batch was dropped (data loss). */
    data class MirrorError(val info: MirrorErrorInfo) : Notice

    /** `system/model_refusal_fallback` — the primary model refused; the turn was retried on a fallback model. */
    data class ModelRefusalFallback(val info: ModelRefusalFallbackInfo) : Notice

    /** `system/informational` — a generic loop text banner (status/hook feedback/slash output) at a render level. */
    data class Informational(val info: InformationalInfo) : Notice

    /** `system/model_refusal_no_fallback` — the model refused and no fallback was configured; the turn ends in error. */
    data class ModelRefusalNoFallback(val info: ModelRefusalNoFallbackInfo) : Notice

    /** `system/worker_shutting_down` — graceful worker teardown with a reason (live-tail signal only). */
    data class WorkerShuttingDown(val info: WorkerShuttingDownInfo) : Notice

    /** Any other (ignored) message; kept for logging/debugging. */
    data class Other(val type: String, val subtype: String?, val raw: JsonObject) : Notice
}

/** Stateless decoder from one NDJSON line to a list of [ClaudeEvent]s (an assistant message fans out per block). */
object ProtocolParser {

    /**
     * Top-level `type` → the decoder for that frame. Same table-not-branch shape as [SYSTEM_DECODERS], and for
     * the same reason: this is a dictionary, and the four `decode(…)` arms differed only in two names.
     */
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
        // Top-level (non-system) types from sdk.d.ts that aren't message/stream frames.
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

    /**
     * `system/<subtype>` → the decoder for that subtype.
     *
     * A table, not a branch. This was a 25-arm `when` in which 21 arms were the SAME expression with two names
     * substituted — the shape of a dictionary written as control flow. As data, adding a protocol subtype is
     * one line, and the shared `decode(…, "system", root)` fallback wiring is written once instead of 21 times
     * (where a mistyped fallback argument would have been invisible).
     */
    private val SYSTEM_DECODERS: Map<String, (JsonObject) -> List<ClaudeEvent>> = buildMap {
        /** The common case: decode the whole frame into [T] and wrap it. */
        fun <T> typed(subtype: String, serializer: kotlinx.serialization.KSerializer<T>, wrap: (T) -> ClaudeEvent) {
            put(subtype) { root -> decode(root, serializer, wrap, "system", root) }
        }
        typed("init", SystemInit.serializer(), ClaudeEvent::Init)
        // E1: typed system subtypes that were previously dropped as Other.
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
        // The four that are not a plain whole-frame decode.
        put("local_command_output") { listOf(ClaudeEvent.LocalCommandOutput(it.str("content").orEmpty())) }
        put("status", ::parseStatus)
        put("compact_boundary", ::parseCompactBoundary)
    }

    private fun parseSystem(root: JsonObject): List<ClaudeEvent> {
        val subtype = root.str("subtype")
        val decoder = SYSTEM_DECODERS[subtype] ?: return listOf(ClaudeEvent.Other("system", subtype, root))
        return decoder(root)
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

    // Locale.ROOT: this goes into fixed English text ("12.5k → 3.2k tokens"), and the default locale would
    // render it "12,5k" on a Spanish or German machine. See StatusLineFormatter.compact for the same fix.
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

            "content_block_delta" -> parseContentBlockDelta(event)

            else -> emptyList()
        }
    }

    private fun parseContentBlockDelta(event: JsonObject): List<ClaudeEvent> {
        val delta = event["delta"] as? JsonObject ?: return emptyList()
        return when (delta.str("type")) {
            "text_delta" -> delta.str("text")?.let { listOf(ClaudeEvent.TextDelta(it)) }.orEmpty()

            // REDACTED thinking (Opus 4.8+): the block streams only a `signature_delta` and any
            // `thinking_delta` carries an EMPTY string — `str()` returns "" (not null), so an unguarded
            // `?.let` used to emit a delta and open an empty "Thought process" fold with nothing in it.
            // Mirror the finalized-block guard: no text, no event.
            "thinking_delta" -> delta.str("thinking")?.takeIf { it.isNotEmpty() }
                ?.let { listOf(ClaudeEvent.ThinkingDelta(it)) }.orEmpty()

            // `signature_delta` (the redacted-thinking signature) carries no displayable content.
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

    /**
     * Strips the CLI's `<tool_use_error>…</tool_use_error>` wrapper from a tool result's text.
     *
     * The binary wraps every failed tool call's `content` in that tag pair — verified against `claude` 2.1.222,
     * where it appears ten times and is emitted as
     * `content: "<tool_use_error>Error: …</tool_use_error>", is_error: true`. The tag is framing for the MODEL,
     * not text for a human: the same message is carried unwrapped in the sibling `toolUseResult` field. Rendered
     * verbatim it put raw markup in the transcript of a native GUI, which is exactly the "never mirror raw CLI
     * output" antipattern this plugin exists to avoid — the failure is already conveyed structurally by
     * `is_error`, which is what paints the card red.
     *
     * Only strips a wrapper that encloses the WHOLE payload, so an error whose body legitimately mentions the
     * tag is left alone. Absent the wrapper this is the identity function, so it costs nothing on the happy path.
     */
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
        return content.filterIsInstance<JsonObject>().mapNotNull { block ->
            if (block.str("type") != "tool_result") return@mapNotNull null
            val toolUseId = block.str("tool_use_id") ?: return@mapNotNull null
            val isError = (block["is_error"] as? JsonPrimitive)?.booleanOrNull ?: false
            val text = when (val c = block["content"]) {
                is JsonPrimitive -> c.contentOrNull.orEmpty()
                is JsonArray -> c.filterIsInstance<JsonObject>().mapNotNull { it.str("text") }.joinToString("\n")
                else -> ""
            }
            ClaudeEvent.ToolResult(toolUseId, unwrapToolError(text), isError, parentToolUseId)
        }
    }

    private fun parseControlRequest(root: JsonObject): List<ClaudeEvent> {
        val requestId = root.str("request_id") ?: return emptyList()
        val request = root["request"] as? JsonObject ?: return emptyList()
        return when (request.str("subtype")) {
            // A malformed can_use_tool MUST NOT throw: the exception would escape the reader loop and the binary
            // would block FOREVER waiting for a permission reply that never comes (the turn hangs). On a decode
            // failure, reply with an error (UnsupportedControlRequest) so the binary is never left waiting —
            // mirroring the elicitation branch below.
            "can_use_tool" -> runCatching {
                listOf(ClaudeEvent.PermissionRequest(requestId, ClaudeJson.decodeFromJsonElement(CanUseToolRequest.serializer(), request)))
            }.getOrDefault(listOf(ClaudeEvent.UnsupportedControlRequest(requestId, "can_use_tool")))

            // hook_callback: the binary fired a hook and blocks on a HookJSONOutput reply (HookBroker owns the decision).
            "hook_callback" -> listOf(ClaudeEvent.HookCallback(requestId, request))

            // request_user_dialog: a tool-driven blocking dialog of an open-union kind — answered {behavior:"cancelled"}.
            "request_user_dialog" -> listOf(
                ClaudeEvent.UserDialogRequest(
                    requestId,
                    request.str("dialog_kind"),
                    (request["payload"] as? JsonObject) ?: JsonObject(emptyMap()),
                    request.str("tool_use_id"),
                ),
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
            ),
        )
    }
}

/** Null-safe string accessor for a [JsonObject] field that is a JSON primitive. */
internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

/** Null-safe int accessor for a [JsonObject] field that is a JSON primitive. */
internal fun JsonObject.intField(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull
