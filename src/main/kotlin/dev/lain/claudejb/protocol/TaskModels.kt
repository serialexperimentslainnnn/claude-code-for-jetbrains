package dev.lain.claudejb.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
//
// The batch is split by message family across this file, SessionSignalModels.kt,
// NoticeModels.kt and HookModels.kt; this header is its verification note for all four.
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
    val status: String? = null, // pending | running | completed | failed | killed | paused
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
    val status: String = "", // completed | failed | stopped
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
    val status: String? = null, // pending | running | completed | failed | killed | paused
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

/** One live background task as reported by the `system/background_tasks_changed` level signal. */
@Serializable
data class BackgroundTaskInfo(
    @SerialName("task_id") val taskId: String = "",
    @SerialName("task_type") val taskType: String = "",
    val description: String = "",
)

/**
 * `system/background_tasks_changed` (SDK 0.3.204) — the FULL set of live background tasks, re-emitted whenever
 * membership changes (start, completion, kill, a foreground agent being backgrounded).
 *
 * A **level** signal with REPLACE semantics: swap the tracked set for [tasks] on every payload, never pair edges.
 * The SDK is explicit that this must NOT be correlated with the `task_started`/`task_notification` edge stream
 * (ordering between them is unspecified). It is per-process — nothing is emitted at startup — so consumers must
 * reset to the empty set whenever the CLI process (re)starts.
 */
@Serializable
data class BackgroundTasksChangedInfo(
    val tasks: List<BackgroundTaskInfo> = emptyList(),
)
