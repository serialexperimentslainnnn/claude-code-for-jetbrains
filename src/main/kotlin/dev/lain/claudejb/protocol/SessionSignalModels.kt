package dev.lain.claudejb.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `system/thinking_tokens` — live estimate of reasoning tokens during redacted thinking. */
@Serializable
data class ThinkingTokensInfo(
    @SerialName("estimated_tokens") val estimatedTokens: Int = 0,
    @SerialName("estimated_tokens_delta") val estimatedTokensDelta: Int = 0,
)

/** `system/session_state_changed` — authoritative turn-state signal (idle/running/requires_action). */
@Serializable
data class SessionStateInfo(
    val state: String = "", // idle | running | requires_action
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

/** `prompt_suggestion` — predicted next user prompt (top-level type, after the result). */
@Serializable
data class PromptSuggestionInfo(
    val suggestion: String = "",
)

/**
 * `system/control_request_progress` (SDK 0.3.204) — progress for a long-running **host-originated** control
 * request (currently only `side_question`, i.e. `/btw`), correlated by [requestId]. [status] is `started` (the
 * worker accepted the request and launched the work) or `api_retry`, which carries the same retry counters as
 * `system/api_retry` and is present only for that status.
 */
@Serializable
data class ControlRequestProgressInfo(
    @SerialName("request_id") val requestId: String = "",
    val status: String = "", // started | api_retry
    val attempt: Int? = null,
    @SerialName("max_retries") val maxRetries: Int? = null,
    @SerialName("retry_delay_ms") val retryDelayMs: Long? = null,
    @SerialName("error_status") val errorStatus: Int? = null,
)
