package dev.lain.claudejb.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    val outcome: String = "", // success | error | cancelled
)
