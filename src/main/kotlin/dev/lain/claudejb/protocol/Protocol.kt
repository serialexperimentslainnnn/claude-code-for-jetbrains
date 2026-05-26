package dev.lain.claudejb.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

    val isWarning: Boolean get() = status == "allowed_warning" || status == "rejected" || overageStatus == "rejected"
    val isExhausted: Boolean get() = status == "rejected" || overageStatus == "rejected"

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
