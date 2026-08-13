package dev.lain.claudejb.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `system/notification` — loop-side text notification mirroring the REPL queue. */
@Serializable
data class NotificationInfo(
    val key: String = "",
    val text: String = "",
    val priority: String = "low", // low | medium | high | immediate
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

/** `system/memory_recall` — memories surfaced into the turn. */
@Serializable
data class MemoryRecallInfo(
    val mode: String = "", // select | synthesize
    val memories: List<RecalledMemory> = emptyList(),
)

@Serializable
data class RecalledMemory(
    val path: String = "",
    val scope: String = "", // personal | team | organization
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

/** `system/plugin_install` — headless plugin install progress. */
@Serializable
data class PluginInstallInfo(
    val status: String = "", // started | installed | failed | completed
    val name: String? = null,
    val error: String? = null,
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
    val direction: String = "retry", // retry | revert | sticky (only "retry" is emitted now)
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
    val level: String = "info", // info | notice | suggestion | warning
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
