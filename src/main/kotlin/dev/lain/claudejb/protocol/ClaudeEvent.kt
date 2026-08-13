package dev.lain.claudejb.protocol

import kotlinx.serialization.json.JsonObject

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

    /** A finalized thinking block. [parentToolUseId] is set when the block comes from inside a subagent
     *  (Task) — exactly as on [AssistantText] — so the reasoning renders in that agent's transcript instead
     *  of the main one, where several agents' "Thought process" rows interleave into something unreadable. */
    data class AssistantThinking(val text: String, val parentToolUseId: String?) : Conversation

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
        /** The tool's STRUCTURED output, when it carried anything this plugin acts on. See [ToolOutputInfo]. */
        val output: ToolOutputInfo? = null,
    ) : Conversation

    /**
     * The fields of `tool_use_result` — the tool's own Output object — that the plugin actually uses.
     *
     * The SDK is explicit that this is the thing to render from: *"Structured tool output — the tool's full
     * Output object, not the string content sent to the model […] render from it instead of parsing the
     * tool_result text"* (`SDKUserMessageReplay.tool_use_result`, SDK 0.3.223). It is also the ONLY place the
     * link between a background task and the tool call that started it exists: `background_tasks_changed`
     * carries `{task_id, task_type, description}` and nothing else, and the SDK forbids pairing that level
     * signal with the edge stream. Without [backgroundTaskId] a background task has no owner, no card to jump
     * to and no output to show — which is exactly how its tabs behaved before 5.5.0 wired this up.
     *
     * Only these four fields are lifted, deliberately: the object is per-tool and open-ended, and carrying it
     * whole would be state nothing reads.
     */
    data class ToolOutputInfo(
        /** `Bash` with `run_in_background`, or any call the user backgrounded: the task's id. */
        val backgroundTaskId: String? = null,
        /** A backgrounded agent's progress file (`AgentOutput.outputFile`) — the one tailable live output. */
        val outputFile: String? = null,
        val stdout: String? = null,
        val stderr: String? = null,
    ) {
        fun isEmpty(): Boolean =
            backgroundTaskId == null && outputFile == null && stdout.isNullOrEmpty() && stderr.isNullOrEmpty()
    }

    // ── Stream ────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Incremental text delta from --include-partial-messages (live streaming preview).
     *
     * [parentToolUseId] names the subagent this run belongs to, and is null for the main conversation. A
     * `stream_event` carries it (`SDKPartialAssistantMessage.parent_tool_use_id`) for the same reason an
     * `assistant` message does, and dropping it here is what let a subagent's streamed text into the main
     * transcript even though its FINAL block was correctly filtered out.
     */
    data class TextDelta(val text: String, val parentToolUseId: String?) : Stream

    /** Incremental thinking delta. [parentToolUseId] as in [TextDelta]. */
    data class ThinkingDelta(val text: String, val parentToolUseId: String?) : Stream

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
