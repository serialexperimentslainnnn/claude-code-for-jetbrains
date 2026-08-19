package dev.lain.claudejb.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

@Serializable
data class ResultMessage(
    val subtype: String = "",
    @SerialName("is_error") val isError: Boolean = false,
    val result: String = "",
    val errors: List<String> = emptyList(),
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("total_cost_usd") val totalCostUsd: Double = 0.0,
    @SerialName("num_turns") val numTurns: Int = 0,
    @SerialName("duration_ms") val durationMs: Long = 0,
)

@Serializable
data class AssistantInner(
    val id: String = "",
    val model: String = "",
    val role: String = "assistant",
    val content: List<kotlinx.serialization.json.JsonObject> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
)
