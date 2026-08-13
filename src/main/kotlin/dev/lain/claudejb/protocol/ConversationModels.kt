package dev.lain.claudejb.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
