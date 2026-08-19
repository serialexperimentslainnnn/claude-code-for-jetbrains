package dev.lain.claudejb.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HookStartedInfo(
    @SerialName("hook_id") val hookId: String = "",
    @SerialName("hook_name") val hookName: String = "",
    @SerialName("hook_event") val hookEvent: String = "",
)

@Serializable
data class HookProgressInfo(
    @SerialName("hook_id") val hookId: String = "",
    @SerialName("hook_name") val hookName: String = "",
    @SerialName("hook_event") val hookEvent: String = "",
    val stdout: String = "",
    val stderr: String = "",
    val output: String = "",
)

@Serializable
data class HookResponseInfo(
    @SerialName("hook_id") val hookId: String = "",
    @SerialName("hook_name") val hookName: String = "",
    @SerialName("hook_event") val hookEvent: String = "",
    val output: String = "",
    val stdout: String = "",
    val stderr: String = "",
    @SerialName("exit_code") val exitCode: Int? = null,
    val outcome: String = "",
)
