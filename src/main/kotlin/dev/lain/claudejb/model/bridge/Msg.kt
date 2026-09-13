package dev.lain.claudejb.model.bridge

import kotlinx.serialization.json.JsonObject

sealed interface Msg {

    sealed interface Prompting : Msg

    sealed interface Settings : Msg

    sealed interface Guard : Settings

    sealed interface RequestCard : Msg

    sealed interface Diffs : Msg

    sealed interface Attachments : Msg

    sealed interface SessionControl : Msg

    sealed interface Vuln : SessionControl

    sealed interface Navigation : SessionControl

    sealed interface Onboarding : SessionControl

    sealed interface Lifecycle : Msg

    sealed interface Log : Msg

    data class Send(val text: String, val scope: String = "") : Prompting
    data class Interrupt(val scope: String = "") : Prompting
    data class RemoveQueued(val index: Int) : Prompting
    data class Copy(val text: String) : Prompting

    object Ready : Lifecycle
    data class Diagnostics(val report: String) : Lifecycle
    data class Unknown(val type: String) : Lifecycle

    data class LogLines(val since: Long) : Log
    data class LogDebug(val on: Boolean) : Log
    data class LogCopy(val level: String) : Log

    data class ChangeModel(val value: String?) : Settings
    data class ChangeMode(val wire: String) : Settings
    data class ChangeEffort(val value: String?) : Settings
    data class ChangeThinking(val on: Boolean) : Settings
    data class ChangeVibe(val on: Boolean) : Settings
    data class ChangeProvider(val id: String) : Settings
    data class SettingsToggle(val key: String, val on: Boolean) : Settings
    object SettingsRefresh : Settings
    object OpenSettings : Settings

    data class GuardSuspend(val rule: String, val duration: String) : Guard
    data class GuardMaster(val on: Boolean, val duration: String) : Guard
    data class GuardWhitelist(val rule: String, val command: String) : Guard
    data class GuardRevokeApproval(val rule: String, val command: String) : Guard
    data class GuardRemoveWhitelist(val rule: String, val command: String) : Guard
    data class GuardAllowAlways(val id: String, val scope: String = "") : Guard
    object GuardLog : Guard
    data class GuardExplain(val id: String) : Guard

    data class ResolvePermission(val id: String, val allow: Boolean, val scope: String = "") : RequestCard
    data class ResolveQuestion(val id: String, val answers: Map<String, String>, val scope: String = "") : RequestCard
    data class ResolveElicitation(
        val id: String,
        val action: String,
        val content: JsonObject?,
        val scope: String = "",
    ) : RequestCard
    data class AlwaysAllow(val tool: String, val id: String, val scope: String = "") : RequestCard

    data class ViewDiff(val id: String, val scope: String = "") : Diffs
    data class ViewDiffByTool(val toolUseId: String) : Diffs
    data class RevertEdit(val toolUseId: String) : Diffs
    data class Open(val url: String) : Diffs
    data class ResolveLinks(val rowId: Long, val paths: List<String>, val symbols: List<String>) : Diffs

    data class RemoveAttachment(val id: String) : Attachments
    data class TreeChildren(val path: String, val mode: String) : Attachments
    data class TreeExpand(val path: String, val mode: String) : Attachments
    data class AttachPaths(val paths: List<String>) : Attachments
    object RequestAttachData : Attachments
    data class AttachPath(val path: String) : Attachments
    object AttachSelection : Attachments
    object AttachCurrentFile : Attachments
    data class PasteClipboardImage(val notify: Boolean) : Attachments
    object PasteClipboard : Attachments
    data class Attach(val name: String, val mediaType: String, val base64: String) : Attachments

    object McpRefresh : SessionControl
    data class McpReconnect(val name: String) : SessionControl
    data class McpToggle(val name: String, val enabled: Boolean) : SessionControl
    data class StopTask(val taskId: String) : SessionControl
    data class SetWorkloadWindow(val minutes: Int) : SessionControl
    data class GitAction(val id: String, val hash: String = "") : SessionControl
    object NewChat : SessionControl
    object CloseThisChat : SessionControl
    object OpenGitView : SessionControl

    object OpenVulnView : Vuln
    data class VulnConsentChoice(val granted: Boolean) : Vuln
    object VulnScan : Vuln
    object VulnCancel : Vuln
    object VulnInventoryRequest : Vuln
    data class VulnFix(val findingId: String) : Vuln
    data class VulnPlan(val tiers: List<String>) : Vuln

    data class RevealAgent(val agentId: String, val toolUseId: String, val chatId: String = "") : Navigation
    data class RevealBackgroundTask(val taskId: String, val chatId: String = "") : Navigation
    data object ShowChatTranscript : Navigation
    data class SelectChat(val chatId: String) : Navigation
    data class CloseChat(val chatId: String) : Navigation
    data class SelectAgent(val agentId: String) : Navigation
    data class CloseAgent(val agentId: String) : Navigation

    data class InstallClaude(val method: String) : Onboarding
    data class SetBinaryPath(val path: String) : Onboarding
    object RecheckBinary : Onboarding
    object LoginSubscription : Onboarding
    object LoginConsole : Onboarding
    data class UseApiKey(val key: String) : Onboarding
    data class SubmitLoginCode(val code: String) : Onboarding
    object CancelLogin : Onboarding
    object DismissAuth : Onboarding
    object Logout : Onboarding
}
