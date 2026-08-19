package dev.lain.claudejb.protocol

import kotlinx.serialization.json.JsonObject

sealed interface ClaudeEvent {

    sealed interface Stream : ClaudeEvent

    sealed interface Conversation : ClaudeEvent

    sealed interface Control : ClaudeEvent

    sealed interface Task : ClaudeEvent

    sealed interface Notice : ClaudeEvent

    sealed interface SessionSignal : ClaudeEvent

    sealed interface HookTelemetry : ClaudeEvent

    data class Init(val info: SystemInit) : Conversation

    data class AssistantText(val text: String, val parentToolUseId: String?) : Conversation

    data class AssistantThinking(val text: String, val parentToolUseId: String?) : Conversation

    data class ToolUse(val id: String, val name: String, val input: JsonObject, val parentToolUseId: String?) : Conversation

    data class ToolResult(
        val toolUseId: String,
        val content: String,
        val isError: Boolean,
        val parentToolUseId: String?,
        val output: ToolOutputInfo? = null,
    ) : Conversation

    data class ToolOutputInfo(
        val backgroundTaskId: String? = null,
        val outputFile: String? = null,
        val stdout: String? = null,
        val stderr: String? = null,
    ) {
        fun isEmpty(): Boolean =
            backgroundTaskId == null && outputFile == null && stdout.isNullOrEmpty() && stderr.isNullOrEmpty()
    }

    data class TextDelta(val text: String, val parentToolUseId: String?) : Stream

    data class ThinkingDelta(val text: String, val parentToolUseId: String?) : Stream

    data class LiveUsage(
        val inputTokens: Int = 0,
        val cacheCreationTokens: Int = 0,
        val cacheReadTokens: Int = 0,
        val outputTokens: Int = 0,
    ) : Stream

    data object MessageStart : Conversation

    data class Result(val result: ResultMessage) : Conversation

    data class LocalCommandOutput(val content: String) : Conversation

    data class StatusNotice(val text: String) : Notice

    data class PermissionRequest(val requestId: String, val request: CanUseToolRequest) : Control

    data class HookCallback(val requestId: String, val request: JsonObject) : Control

    data class UserDialogRequest(
        val requestId: String,
        val dialogKind: String?,
        val payload: JsonObject,
        val toolUseId: String?,
    ) : Control

    data class Elicitation(val requestId: String, val request: ElicitationRequest) : Control

    data class UnsupportedControlRequest(val requestId: String, val subtype: String?) : Control

    data class ControlResult(
        val requestId: String,
        val success: Boolean,
        val payload: JsonObject?,
        val error: String?,
    ) : Control

    data class TaskStarted(val info: TaskStartedInfo) : Task

    data class TaskProgress(val info: TaskProgressInfo) : Task

    data class TaskUpdated(val info: TaskUpdatedInfo) : Task

    data class TaskNotification(val info: TaskNotificationInfo) : Task

    data class ToolProgress(val info: ToolProgressInfo) : Task

    data class ToolUseSummary(val info: ToolUseSummaryInfo) : Task

    data class BackgroundTasksChanged(val info: BackgroundTasksChangedInfo) : Task

    data class RateLimit(val info: RateLimitInfo) : SessionSignal

    data class ThinkingTokens(val info: ThinkingTokensInfo) : SessionSignal

    data class SessionStateChanged(val info: SessionStateInfo) : SessionSignal

    data class AuthStatus(val info: AuthStatusInfo) : SessionSignal

    data class ApiRetry(val info: ApiRetryInfo) : SessionSignal

    data class CommandsChanged(val info: CommandsChangedInfo) : SessionSignal

    data class PromptSuggestion(val info: PromptSuggestionInfo) : SessionSignal

    data class ControlRequestProgress(val info: ControlRequestProgressInfo) : SessionSignal

    data class HookStarted(val info: HookStartedInfo) : HookTelemetry

    data class HookProgress(val info: HookProgressInfo) : HookTelemetry

    data class HookResponse(val info: HookResponseInfo) : HookTelemetry

    data class Notification(val info: NotificationInfo) : Notice

    data class PermissionDenied(val info: PermissionDeniedInfo) : Notice

    data class MemoryRecall(val info: MemoryRecallInfo) : Notice

    data class FilesPersisted(val info: FilesPersistedInfo) : Notice

    data class PluginInstall(val info: PluginInstallInfo) : Notice

    data class MirrorError(val info: MirrorErrorInfo) : Notice

    data class ModelRefusalFallback(val info: ModelRefusalFallbackInfo) : Notice

    data class Informational(val info: InformationalInfo) : Notice

    data class ModelRefusalNoFallback(val info: ModelRefusalNoFallbackInfo) : Notice

    data class WorkerShuttingDown(val info: WorkerShuttingDownInfo) : Notice

    data class Other(val type: String, val subtype: String?, val raw: JsonObject) : Notice
}
