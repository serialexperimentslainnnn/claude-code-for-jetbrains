package dev.lain.claudejb.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ThinkingTokensInfo(
    @SerialName("estimated_tokens") val estimatedTokens: Int = 0,
    @SerialName("estimated_tokens_delta") val estimatedTokensDelta: Int = 0,
)

@Serializable
data class SessionStateInfo(
    val state: String = "",
)

@Serializable
data class AuthStatusInfo(
    val isAuthenticating: Boolean = false,
    val output: List<String> = emptyList(),
    val error: String? = null,
)

@Serializable
data class ApiRetryInfo(
    val attempt: Int = 0,
    @SerialName("max_retries") val maxRetries: Int = 0,
    @SerialName("retry_delay_ms") val retryDelayMs: Long = 0,
    @SerialName("error_status") val errorStatus: Int? = null,
    val error: String? = null,
)

@Serializable
data class CommandsChangedInfo(
    val commands: List<SlashCommand> = emptyList(),
)

@Serializable
data class PromptSuggestionInfo(
    val suggestion: String = "",
)

@Serializable
data class ControlRequestProgressInfo(
    @SerialName("request_id") val requestId: String = "",
    val status: String = "",
    val attempt: Int? = null,
    @SerialName("max_retries") val maxRetries: Int? = null,
    @SerialName("retry_delay_ms") val retryDelayMs: Long? = null,
    @SerialName("error_status") val errorStatus: Int? = null,
)
