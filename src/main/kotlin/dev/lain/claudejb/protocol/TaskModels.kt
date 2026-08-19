package dev.lain.claudejb.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TaskUsage(
    @SerialName("total_tokens") val totalTokens: Long = 0,
    @SerialName("tool_uses") val toolUses: Int = 0,
    @SerialName("duration_ms") val durationMs: Long = 0,
)

@Serializable
data class TaskProgressInfo(
    @SerialName("task_id") val taskId: String = "",
    @SerialName("tool_use_id") val toolUseId: String? = null,
    val description: String = "",
    @SerialName("subagent_type") val subagentType: String? = null,
    val usage: TaskUsage = TaskUsage(),
    @SerialName("last_tool_name") val lastToolName: String? = null,
    val summary: String? = null,
    val status: String? = null,
    val error: String? = null,
)

@Serializable
data class TaskStartedInfo(
    @SerialName("task_id") val taskId: String = "",
    @SerialName("tool_use_id") val toolUseId: String? = null,
    val description: String = "",
    @SerialName("subagent_type") val subagentType: String? = null,
    @SerialName("task_type") val taskType: String? = null,
    @SerialName("workflow_name") val workflowName: String? = null,
    val prompt: String? = null,
    @SerialName("skip_transcript") val skipTranscript: Boolean = false,
)

@Serializable
data class TaskNotificationInfo(
    @SerialName("task_id") val taskId: String = "",
    @SerialName("tool_use_id") val toolUseId: String? = null,
    val status: String = "",
    @SerialName("output_file") val outputFile: String = "",
    val summary: String = "",
    val usage: TaskUsage? = null,
    @SerialName("skip_transcript") val skipTranscript: Boolean = false,
)

@Serializable
data class TaskUpdatedInfo(
    @SerialName("task_id") val taskId: String = "",
    val patch: TaskPatch = TaskPatch(),
)

@Serializable
data class TaskPatch(
    val status: String? = null,
    val description: String? = null,
    @SerialName("end_time") val endTime: Long? = null,
    @SerialName("total_paused_ms") val totalPausedMs: Long? = null,
    val error: String? = null,
    @SerialName("is_backgrounded") val isBackgrounded: Boolean? = null,
)

@Serializable
data class ToolProgressInfo(
    @SerialName("tool_use_id") val toolUseId: String = "",
    @SerialName("tool_name") val toolName: String = "",
    @SerialName("parent_tool_use_id") val parentToolUseId: String? = null,
    @SerialName("elapsed_time_seconds") val elapsedTimeSeconds: Double = 0.0,
    @SerialName("task_id") val taskId: String? = null,
)

@Serializable
data class ToolUseSummaryInfo(
    val summary: String = "",
    @SerialName("preceding_tool_use_ids") val precedingToolUseIds: List<String> = emptyList(),
)

@Serializable
data class BackgroundTaskInfo(
    @SerialName("task_id") val taskId: String = "",
    @SerialName("task_type") val taskType: String = "",
    val description: String = "",
)

@Serializable
data class BackgroundTasksChangedInfo(
    val tasks: List<BackgroundTaskInfo> = emptyList(),
)
