package dev.lain.claudejb.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Single tolerant [Json] instance for the whole stream-json protocol.
 *
 * The Claude Code control protocol is broad (dozens of message and control subtypes, many of which
 * this plugin ignores) and evolves between binary versions, so decoding is deliberately lenient:
 * unknown keys/types must never crash the reader loop.
 *
 * Incoming messages are decoded with the typed models below; outgoing messages are built explicitly
 * as [kotlinx.serialization.json.JsonObject]s in [ControlProtocol] to keep their wire shape exact.
 */
val ClaudeJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

// ---------------------------------------------------------------------------
// Incoming SDKMessage payloads (subset we care about). Verified against
// node_modules/@anthropic-ai/claude-agent-sdk/sdk.d.ts (claudeCodeVersion 2.1.150).
// ---------------------------------------------------------------------------

/** `{"type":"system","subtype":"init", ...}` — first message; carries the session id to --resume. */
@Serializable
data class SystemInit(
    @SerialName("session_id") val sessionId: String = "",
    val model: String = "",
    val cwd: String = "",
    val tools: List<String> = emptyList(),
    @SerialName("slash_commands") val slashCommands: List<String> = emptyList(),
    @SerialName("permissionMode") val permissionMode: String = "default",
    @SerialName("mcp_servers") val mcpServers: List<McpServerStatus> = emptyList(),
    @SerialName("output_style") val outputStyle: String = "default",
    @SerialName("claude_code_version") val claudeCodeVersion: String = "",
)

@Serializable
data class McpServerStatus(val name: String = "", val status: String = "")

/**
 * `{"type":"result","subtype":"success|error_*", ...}` — end of a turn. Watching for this is how
 * the host knows the agent is idle again and can flush the next queued (multiprompt) message.
 */
@Serializable
data class ResultMessage(
    val subtype: String = "",
    @SerialName("is_error") val isError: Boolean = false,
    val result: String = "",
    // error_* subtypes carry no `result`; their message(s) arrive here (sdk.d.ts SDKResultError.errors).
    val errors: List<String> = emptyList(),
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("total_cost_usd") val totalCostUsd: Double = 0.0,
    @SerialName("num_turns") val numTurns: Int = 0,
    @SerialName("duration_ms") val durationMs: Long = 0,
)

/** Inner Anthropic BetaMessage of `{"type":"assistant","message":{...}}`. Content blocks are dispatched manually. */
@Serializable
data class AssistantInner(
    val id: String = "",
    val model: String = "",
    val role: String = "assistant",
    val content: List<kotlinx.serialization.json.JsonObject> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
)

// ---------------------------------------------------------------------------
// Control protocol: can_use_tool request (binary -> host) and its data.
// ---------------------------------------------------------------------------

/** Inner payload of a `can_use_tool` control_request. The hook that drives native diff review. */
@Serializable
data class CanUseToolRequest(
    @SerialName("tool_name") val toolName: String = "",
    val input: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
    val title: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val description: String? = null,
    @SerialName("tool_use_id") val toolUseId: String = "",
    @SerialName("blocked_path") val blockedPath: String? = null,
    /** Explains why this permission request was triggered (e.g. a deny rule, an out-of-root path). */
    @SerialName("decision_reason") val decisionReason: String? = null,
)

// ---------------------------------------------------------------------------
// AskUserQuestion: a built-in tool delivered as a can_use_tool whose input carries
// the questions. The host renders them and replies allow with updatedInput = input +
// {"answers": {questionText: chosenLabel}} (multi-select labels are comma-joined).
// Verified empirically against claude 2.1.150 (the result echoes the chosen option).
// ---------------------------------------------------------------------------

@Serializable
data class AskQuestion(
    val question: String = "",
    val header: String = "",
    val options: List<AskOption> = emptyList(),
    val multiSelect: Boolean = false,
)

@Serializable
data class AskOption(
    val label: String = "",
    val description: String = "",
    val preview: String? = null,
)

/** Parses the `questions` array out of an AskUserQuestion tool input; empty if malformed. */
fun parseAskQuestions(input: kotlinx.serialization.json.JsonObject): List<AskQuestion> {
    val arr = input["questions"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return runCatching {
        arr.map { ClaudeJson.decodeFromJsonElement(AskQuestion.serializer(), it) }
    }.getOrDefault(emptyList())
}

// ---------------------------------------------------------------------------
// elicitation control request (binary -> host): an MCP server asks the user for
// input. URL mode points at a link to complete (e.g. an OAuth flow); form mode
// carries a JSON-schema `requested_schema` whose primitive properties become input
// fields. Answered with ElicitResult {action: accept|decline|cancel, content?}.
// Verified against SDKControlElicitationRequest in sdk.d.ts.
// ---------------------------------------------------------------------------

@Serializable
data class ElicitationRequest(
    @SerialName("mcp_server_name") val mcpServerName: String = "",
    val message: String = "",
    val mode: String? = null,                          // "form" | "url" | null
    val url: String? = null,
    @SerialName("elicitation_id") val elicitationId: String? = null,
    @SerialName("requested_schema") val requestedSchema: JsonObject? = null,
    val title: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val description: String? = null,
)

/** One primitive input field extracted from an elicitation's requested_schema. */
data class ElicitField(
    val name: String,
    val type: String,                                  // string | number | integer | boolean
    val title: String?,
    val required: Boolean,
)

private val PRIMITIVE_ELICIT_TYPES = setOf("string", "number", "integer", "boolean")

/**
 * Extracts the flat primitive fields of an elicitation `requested_schema` (a JSON-schema object): one
 * [ElicitField] per `properties` entry whose `type` is string/number/integer/boolean. Returns an empty list
 * when the schema is absent, malformed, or carries any nested/object property — the caller then falls back to
 * a plain Accept-with-no-content card. Never throws.
 */
fun parseElicitationFields(schema: JsonObject?): List<ElicitField> {
    schema ?: return emptyList()
    return runCatching {
        val props = schema["properties"] as? JsonObject ?: return emptyList()
        val required = (schema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toSet().orEmpty()
        val fields = ArrayList<ElicitField>(props.size)
        for ((name, spec) in props) {
            val obj = spec as? JsonObject ?: return emptyList()
            val type = obj.str("type") ?: return emptyList()
            if (type !in PRIMITIVE_ELICIT_TYPES) return emptyList()
            fields += ElicitField(name, type, obj.str("title"), name in required)
        }
        fields
    }.getOrDefault(emptyList())
}

// ---------------------------------------------------------------------------
// initialize handshake response (binary -> host): rich command + model metadata.
// ---------------------------------------------------------------------------

@Serializable
data class InitializeResponse(
    val commands: List<SlashCommand> = emptyList(),
    val models: List<ModelInfo> = emptyList(),
    val agents: List<AgentInfo> = emptyList(),
    @SerialName("output_style") val outputStyle: String = "default",
    @SerialName("available_output_styles") val availableOutputStyles: List<String> = emptyList(),
    val account: AccountInfo = AccountInfo(),
)

@Serializable
data class AgentInfo(
    val name: String = "",
    val description: String = "",
)

@Serializable
data class AccountInfo(
    val email: String = "",
    val organization: String = "",
    val subscriptionType: String = "",
    /** Auth backend reported by the binary (firstParty/bedrock/vertex/foundry/anthropicAws/mantle/gateway). */
    val apiProvider: String = "",
    /** Where the API key (if any) came from (e.g. env var, helper script). */
    val apiKeySource: String = "",
)

/** A slash command as reported by the binary: name (no slash), description, argument hint, aliases. */
@Serializable
data class SlashCommand(
    val name: String,
    val description: String = "",
    val argumentHint: String = "",
    val aliases: List<String> = emptyList(),
)

@Serializable
data class ModelInfo(
    val value: String,
    val displayName: String = "",
    val description: String = "",
    /** Whether `--effort` is meaningful for this model (Opus 4.7+ supports it; Haiku does not). */
    val supportsEffort: Boolean = false,
    /** Effort levels the model accepts (e.g. ["low","medium","high","xhigh","max"]). */
    val supportedEffortLevels: List<String> = emptyList(),
    /** Whether adaptive extended thinking is supported (drives `--thinking adaptive`). */
    val supportsAdaptiveThinking: Boolean = false,
    /** Whether the model supports the binary's "fast mode" (no reasoning, lowest latency). */
    val supportsFastMode: Boolean = false,
    /** Whether the model supports "auto mode" (binary picks effort/thinking per turn). */
    val supportsAutoMode: Boolean = false,
)

// ---------------------------------------------------------------------------
// get_context_usage response (binary -> host): drives the context meter (/context).
// ---------------------------------------------------------------------------

@Serializable
data class ContextUsage(
    val totalTokens: Long = 0,
    val maxTokens: Long = 0,
    val percentage: Double = 0.0,
    val categories: List<ContextCategory> = emptyList(),
)

@Serializable
data class ContextCategory(
    val name: String = "",
    val tokens: Long = 0,
)

/**
 * `apiUsage` block of the `get_session_cost` control response: the binary's **authoritative cumulative**
 * token tally for the session (the same figures the Anthropic API returns). Preferred over locally-folded
 * counters for display, which can drift. Matches `SDKControlGetSessionCostResponse.apiUsage` in sdk.d.ts.
 */
@Serializable
data class SessionCostUsage(
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Long = 0,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Long = 0,
)

// ---------------------------------------------------------------------------
// rate_limit_event (binary -> host): subscription quota usage for claude.ai users.
// `utilization` is only present when the binary has it (typically near the limit).
// ---------------------------------------------------------------------------

@Serializable
data class RateLimitInfo(
    val status: String = "allowed",            // allowed | allowed_warning | rejected
    val resetsAt: Long? = null,                // epoch seconds when this window resets
    val rateLimitType: String? = null,         // five_hour | seven_day | seven_day_opus/sonnet | overage
    val utilization: Double? = null,           // % of quota used (0..100, sometimes 0..1)
    val overageStatus: String? = null,
    val isUsingOverage: Boolean = false,
    val surpassedThreshold: Double? = null,
) {
    /** Normalized 0..100 percent, or null if the binary didn't report utilization. */
    fun utilizationPercent(): Int? = utilization?.let {
        (if (it <= 1.0) it * 100 else it).toInt().coerceIn(0, 100)
    }

    val isWarning: Boolean get() = status == "allowed_warning" || status == "rejected"
    val isExhausted: Boolean get() = status == "rejected"

    /** Short window label for the UI (e.g. "5h", "7d"). */
    fun windowLabel(): String = when (rateLimitType) {
        "five_hour" -> "5h"
        "seven_day" -> "7d"
        "seven_day_opus" -> "7d Opus"
        "seven_day_sonnet" -> "7d Sonnet"
        "overage" -> "overage"
        else -> "quota"
    }
}

// ---------------------------------------------------------------------------
// Additional system/* and stream events (E1). Verified against sdk.d.ts
// (SDKTaskProgressMessage, SDKTaskNotificationMessage, SDKTaskStartedMessage,
// SDKTaskUpdatedMessage, SDKToolProgressMessage, SDKToolUseSummaryMessage,
// SDKThinkingTokensMessage, SDKNotificationMessage, SDKPermissionDeniedMessage,
// SDKSessionStateChangedMessage, SDKAuthStatusMessage, SDKAPIRetryMessage,
// SDKCommandsChangedMessage, SDKMemoryRecallMessage, SDKFilesPersistedEvent,
// SDKPromptSuggestionMessage, SDKPluginInstallMessage, SDKHookStartedMessage,
// SDKHookProgressMessage, SDKHookResponseMessage, SDKMirrorErrorMessage).
// All fields optional with defaults so a missing/renamed key never crashes the reader.
// ---------------------------------------------------------------------------

/** Per-subagent token/tool accounting carried by task_progress / task_notification. */
@Serializable
data class TaskUsage(
    @SerialName("total_tokens") val totalTokens: Long = 0,
    @SerialName("tool_uses") val toolUses: Int = 0,
    @SerialName("duration_ms") val durationMs: Long = 0,
)

/** `system/task_progress` — periodic progress for a running subagent (Task tool). */
@Serializable
data class TaskProgressInfo(
    @SerialName("task_id") val taskId: String = "",
    @SerialName("tool_use_id") val toolUseId: String? = null,
    val description: String = "",
    @SerialName("subagent_type") val subagentType: String? = null,
    val usage: TaskUsage = TaskUsage(),
    @SerialName("last_tool_name") val lastToolName: String? = null,
    val summary: String? = null,
    // Mutable lifecycle fields a `task_updated` patch can flip (running → paused/failed/…); surfaced by the UI.
    val status: String? = null,                    // pending | running | completed | failed | killed | paused
    val error: String? = null,
)

/** `system/task_started` — a subagent task began. */
@Serializable
data class TaskStartedInfo(
    @SerialName("task_id") val taskId: String = "",
    @SerialName("tool_use_id") val toolUseId: String? = null,
    val description: String = "",
    @SerialName("subagent_type") val subagentType: String? = null,
    @SerialName("task_type") val taskType: String? = null,
    @SerialName("workflow_name") val workflowName: String? = null,
    val prompt: String? = null,
    /** Ambient/housekeeping task — hide from inline transcript (may still show in a tasks panel). */
    @SerialName("skip_transcript") val skipTranscript: Boolean = false,
)

/** `system/task_notification` — a subagent settled (completed/failed/stopped). */
@Serializable
data class TaskNotificationInfo(
    @SerialName("task_id") val taskId: String = "",
    @SerialName("tool_use_id") val toolUseId: String? = null,
    val status: String = "",                       // completed | failed | stopped
    @SerialName("output_file") val outputFile: String = "",
    val summary: String = "",
    val usage: TaskUsage? = null,
    @SerialName("skip_transcript") val skipTranscript: Boolean = false,
)

/** `system/task_updated` — a wire-safe patch of changed TaskState fields; clients merge into their task map. */
@Serializable
data class TaskUpdatedInfo(
    @SerialName("task_id") val taskId: String = "",
    val patch: TaskPatch = TaskPatch(),
)

@Serializable
data class TaskPatch(
    val status: String? = null,                    // pending | running | completed | failed | killed | paused
    val description: String? = null,
    @SerialName("end_time") val endTime: Long? = null,
    @SerialName("total_paused_ms") val totalPausedMs: Long? = null,
    val error: String? = null,
    @SerialName("is_backgrounded") val isBackgrounded: Boolean? = null,
)

/** `tool_progress` — heartbeat for a long-running tool (top-level type, not system). */
@Serializable
data class ToolProgressInfo(
    @SerialName("tool_use_id") val toolUseId: String = "",
    @SerialName("tool_name") val toolName: String = "",
    @SerialName("parent_tool_use_id") val parentToolUseId: String? = null,
    @SerialName("elapsed_time_seconds") val elapsedTimeSeconds: Double = 0.0,
    @SerialName("task_id") val taskId: String? = null,
)

/** `tool_use_summary` — a one-line summary that covers several preceding tool_use ids. */
@Serializable
data class ToolUseSummaryInfo(
    val summary: String = "",
    @SerialName("preceding_tool_use_ids") val precedingToolUseIds: List<String> = emptyList(),
)

/** `system/thinking_tokens` — live estimate of reasoning tokens during redacted thinking. */
@Serializable
data class ThinkingTokensInfo(
    @SerialName("estimated_tokens") val estimatedTokens: Int = 0,
    @SerialName("estimated_tokens_delta") val estimatedTokensDelta: Int = 0,
)

/** `system/notification` — loop-side text notification mirroring the REPL queue. */
@Serializable
data class NotificationInfo(
    val key: String = "",
    val text: String = "",
    val priority: String = "low",                  // low | medium | high | immediate
    val color: String? = null,
    @SerialName("timeout_ms") val timeoutMs: Long? = null,
)

/** `system/permission_denied` — a tool call auto-denied without an interactive prompt. */
@Serializable
data class PermissionDeniedInfo(
    @SerialName("tool_name") val toolName: String = "",
    @SerialName("tool_use_id") val toolUseId: String = "",
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("decision_reason_type") val decisionReasonType: String? = null,
    @SerialName("decision_reason") val decisionReason: String? = null,
    val message: String = "",
)

/** `system/session_state_changed` — authoritative turn-state signal (idle/running/requires_action). */
@Serializable
data class SessionStateInfo(
    val state: String = "",                        // idle | running | requires_action
)

/** `auth_status` — top-level type (not system). Auth backend (re)authenticating. */
@Serializable
data class AuthStatusInfo(
    val isAuthenticating: Boolean = false,
    val output: List<String> = emptyList(),
    val error: String? = null,
)

/** `system/api_retry` — a retryable API failure that will be retried after a delay. */
@Serializable
data class ApiRetryInfo(
    val attempt: Int = 0,
    @SerialName("max_retries") val maxRetries: Int = 0,
    @SerialName("retry_delay_ms") val retryDelayMs: Long = 0,
    @SerialName("error_status") val errorStatus: Int? = null,
    val error: String? = null,
)

/** `system/commands_changed` — full replacement slash-command list pushed mid-session. */
@Serializable
data class CommandsChangedInfo(
    val commands: List<SlashCommand> = emptyList(),
)

/** `system/memory_recall` — memories surfaced into the turn. */
@Serializable
data class MemoryRecallInfo(
    val mode: String = "",                         // select | synthesize
    val memories: List<RecalledMemory> = emptyList(),
)

@Serializable
data class RecalledMemory(
    val path: String = "",
    val scope: String = "",                        // personal | team | organization
    val content: String? = null,
)

/** `system/files_persisted` — files uploaded to the Files API (and any that failed). */
@Serializable
data class FilesPersistedInfo(
    val files: List<PersistedFile> = emptyList(),
    val failed: List<FailedFile> = emptyList(),
    @SerialName("processed_at") val processedAt: String = "",
)

@Serializable
data class PersistedFile(
    val filename: String = "",
    @SerialName("file_id") val fileId: String = "",
)

@Serializable
data class FailedFile(
    val filename: String = "",
    val error: String = "",
)

/** `prompt_suggestion` — predicted next user prompt (top-level type, after the result). */
@Serializable
data class PromptSuggestionInfo(
    val suggestion: String = "",
)

/** `system/plugin_install` — headless plugin install progress. */
@Serializable
data class PluginInstallInfo(
    val status: String = "",                       // started | installed | failed | completed
    val name: String? = null,
    val error: String? = null,
)

/** `system/hook_started` — a hook callback began executing. */
@Serializable
data class HookStartedInfo(
    @SerialName("hook_id") val hookId: String = "",
    @SerialName("hook_name") val hookName: String = "",
    @SerialName("hook_event") val hookEvent: String = "",
)

/** `system/hook_progress` — streaming stdout/stderr from a running hook. */
@Serializable
data class HookProgressInfo(
    @SerialName("hook_id") val hookId: String = "",
    @SerialName("hook_name") val hookName: String = "",
    @SerialName("hook_event") val hookEvent: String = "",
    val stdout: String = "",
    val stderr: String = "",
    val output: String = "",
)

/** `system/hook_response` — a hook finished (success/error/cancelled). */
@Serializable
data class HookResponseInfo(
    @SerialName("hook_id") val hookId: String = "",
    @SerialName("hook_name") val hookName: String = "",
    @SerialName("hook_event") val hookEvent: String = "",
    val output: String = "",
    val stdout: String = "",
    val stderr: String = "",
    @SerialName("exit_code") val exitCode: Int? = null,
    val outcome: String = "",                       // success | error | cancelled
)

/** `system/mirror_error` — the binary's transcript-mirror batch was dropped after retries (data loss). */
@Serializable
data class MirrorErrorInfo(
    val error: String = "",
    val key: MirrorErrorKey = MirrorErrorKey(),
)

@Serializable
data class MirrorErrorKey(
    val projectKey: String = "",
    val sessionId: String = "",
    val subpath: String? = null,
)

/**
 * `system/model_refusal_fallback` — the primary model ended the stream with stop_reason "refusal" and the
 * turn was retried once on [fallbackModel] (the swap is made persistent for the session; `direction:"retry"`).
 * "revert"/"sticky" are retained in the enum for SDK-consumer compat and are no longer emitted. The refused
 * partial leg is retracted: [retractedMessageUuids] names the wire uuids to evict (idempotent on receipt).
 * [content] is human-readable display prose. [apiRefusalCategory] is an open string ("cyber", "bio", …).
 */
@Serializable
data class ModelRefusalFallbackInfo(
    val trigger: String = "refusal",
    val direction: String = "retry",               // retry | revert | sticky (only "retry" is emitted now)
    @SerialName("original_model") val originalModel: String = "",
    @SerialName("fallback_model") val fallbackModel: String = "",
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("api_refusal_category") val apiRefusalCategory: String? = null,
    @SerialName("api_refusal_explanation") val apiRefusalExplanation: String? = null,
    @SerialName("retracted_message_uuids") val retractedMessageUuids: List<String> = emptyList(),
    val content: String = "",
)

/**
 * `system/informational` (SDK 0.3.193) — a generic text banner from the loop: non-error status lines, hook
 * feedback (e.g. a UserPromptSubmit hook's block reason), slash-command output. [level] drives prominence
 * (info | notice | suggestion | warning). [preventContinuation] means execution stops after this message.
 */
@Serializable
data class InformationalInfo(
    val content: String = "",
    val level: String = "info",                    // info | notice | suggestion | warning
    @SerialName("tool_use_id") val toolUseId: String? = null,
    @SerialName("prevent_continuation") val preventContinuation: Boolean = false,
)

/**
 * `system/model_refusal_no_fallback` (SDK 0.3.193) — the model ended the stream with stop_reason "refusal" and
 * NO fallback model was configured, so the turn ends as an error. The structured counterpart to detecting a
 * refusal on the assistant error frame. [content] is human-readable display prose.
 */
@Serializable
data class ModelRefusalNoFallbackInfo(
    @SerialName("original_model") val originalModel: String = "",
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("api_refusal_category") val apiRefusalCategory: String? = null,
    @SerialName("api_refusal_explanation") val apiRefusalExplanation: String? = null,
    @SerialName("refused_user_message_uuid") val refusedUserMessageUuid: String? = null,
    val content: String = "",
)

/**
 * `system/worker_shutting_down` (SDK 0.3.193) — graceful worker teardown with a host-set [reason] (e.g.
 * `host_exit`, `remote_control_disabled`). A LIVE-TAIL signal only: a resumed session may replay historical
 * instances mid-stream, so it's honored as informational and never treated as a session-lifetime fact.
 */
@Serializable
data class WorkerShuttingDownInfo(
    val reason: String = "",
)
