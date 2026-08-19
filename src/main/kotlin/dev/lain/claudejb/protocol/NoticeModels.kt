package dev.lain.claudejb.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NotificationInfo(
    val key: String = "",
    val text: String = "",
    val priority: String = "low",
    val color: String? = null,
    @SerialName("timeout_ms") val timeoutMs: Long? = null,
)

@Serializable
data class PermissionDeniedInfo(
    @SerialName("tool_name") val toolName: String = "",
    @SerialName("tool_use_id") val toolUseId: String = "",
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("decision_reason_type") val decisionReasonType: String? = null,
    @SerialName("decision_reason") val decisionReason: String? = null,
    val message: String = "",
)

@Serializable
data class MemoryRecallInfo(
    val mode: String = "",
    val memories: List<RecalledMemory> = emptyList(),
)

@Serializable
data class RecalledMemory(
    val path: String = "",
    val scope: String = "",
    val content: String? = null,
)

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

@Serializable
data class PluginInstallInfo(
    val status: String = "",
    val name: String? = null,
    val error: String? = null,
)

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

@Serializable
data class ModelRefusalFallbackInfo(
    val trigger: String = "refusal",
    val direction: String = "retry",
    @SerialName("original_model") val originalModel: String = "",
    @SerialName("fallback_model") val fallbackModel: String = "",
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("api_refusal_category") val apiRefusalCategory: String? = null,
    @SerialName("api_refusal_explanation") val apiRefusalExplanation: String? = null,
    @SerialName("retracted_message_uuids") val retractedMessageUuids: List<String> = emptyList(),
    val content: String = "",
)

@Serializable
data class InformationalInfo(
    val content: String = "",
    val level: String = "info",
    @SerialName("tool_use_id") val toolUseId: String? = null,
    @SerialName("prevent_continuation") val preventContinuation: Boolean = false,
)

@Serializable
data class ModelRefusalNoFallbackInfo(
    @SerialName("original_model") val originalModel: String = "",
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("api_refusal_category") val apiRefusalCategory: String? = null,
    @SerialName("api_refusal_explanation") val apiRefusalExplanation: String? = null,
    @SerialName("refused_user_message_uuid") val refusedUserMessageUuid: String? = null,
    val content: String = "",
)

@Serializable
data class WorkerShuttingDownInfo(
    val reason: String = "",
)
