package dev.lain.claudejb.ui.jcef

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

object JcefBridge {

    private val lenient = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    const val SCOPE_GIT = "git"

    sealed interface Msg {

        sealed interface Prompting : Msg

        sealed interface Settings : Msg

        sealed interface Guard : Settings

        sealed interface RequestCard : Msg

        sealed interface Diffs : Msg

        sealed interface Attachments : Msg

        sealed interface SessionControl : Msg

        sealed interface Lifecycle : Msg

        data class Send(val text: String, val scope: String = "") : Prompting

        data class Interrupt(val scope: String = "") : Prompting
        object CycleMode : Prompting
        data class RemoveQueued(val index: Int) : Prompting
        data class Copy(val text: String) : Prompting

        object Ready : Lifecycle

        data class Diagnostics(val report: String) : Lifecycle

        data class Unknown(val type: String) : Lifecycle

        data class ChangeModel(val value: String?) : Settings
        data class ChangeMode(val wire: String) : Settings
        data class ChangeEffort(val value: String?) : Settings
        data class ChangeThinking(val on: Boolean) : Settings
        data class ChangeVibe(val on: Boolean) : Settings
        data class ChangeProvider(val id: String) : Settings

        data class SettingsToggle(val key: String, val on: Boolean) : Settings

        data class GuardSuspend(val rule: String, val duration: String) : Guard

        data class GuardMaster(val on: Boolean, val duration: String) : Guard

        data class GuardWhitelist(val rule: String, val command: String) : Guard

        data class GuardRevokeApproval(val rule: String, val command: String) : Guard

        data class GuardRemoveWhitelist(val rule: String, val command: String) : Guard

        data class GuardAllowAlways(val id: String, val scope: String = "") : Guard

        object GuardLog : Guard

        data class GuardExplain(val id: String) : Guard

        object SettingsRefresh : Settings

        object OpenSettings : Settings

        data class ResolvePermission(val id: String, val allow: Boolean, val scope: String = "") : RequestCard
        data class ResolveQuestion(
            val id: String,
            val answers: Map<String, String>,
            val scope: String = "",
        ) : RequestCard

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

        data class McpReconnect(val name: String) : SessionControl
        data class McpToggle(val name: String, val enabled: Boolean) : SessionControl
        data class StopTask(val taskId: String) : SessionControl

        data class SetWorkloadWindow(val minutes: Int) : SessionControl

        data class GitAction(val id: String, val hash: String = "") : SessionControl

        object NewChat : SessionControl

        object CloseThisChat : SessionControl

        object OpenGitView : SessionControl

        object OpenVulnView : SessionControl

        data class VulnConsentChoice(val granted: Boolean) : SessionControl

        object VulnScan : SessionControl

        object VulnCancel : SessionControl

        object VulnInventoryRequest : SessionControl

        data class VulnFix(val findingId: String) : SessionControl

        data class VulnPlan(val tiers: List<String>) : SessionControl

        data class RevealAgent(val agentId: String, val toolUseId: String, val chatId: String = "") :
            SessionControl

        data class RevealBackgroundTask(val taskId: String, val chatId: String = "") : SessionControl

        data object ShowChatTranscript : SessionControl

        data class SelectChat(val chatId: String) : SessionControl
        data class CloseChat(val chatId: String) : SessionControl
        data class SelectAgent(val agentId: String) : SessionControl
        data class CloseAgent(val agentId: String) : SessionControl

        data class InstallClaude(val method: String) : SessionControl
        data class SetBinaryPath(val path: String) : SessionControl
        object RecheckBinary : SessionControl

        object LoginSubscription : SessionControl

        object LoginConsole : SessionControl
        data class UseApiKey(val key: String) : SessionControl
        data class SubmitLoginCode(val code: String) : SessionControl
        object CancelLogin : SessionControl
        object DismissAuth : SessionControl
        object Logout : SessionControl
    }

    private class Fields(val obj: JsonObject) {
        fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.contentOrNull
        fun text(key: String): String = str(key).orEmpty()
        fun bool(key: String): Boolean = (obj[key] as? JsonPrimitive)?.booleanOrNull ?: false
        fun int(key: String, fallback: Int): Int = (obj[key] as? JsonPrimitive)?.intOrNull ?: fallback
        fun long(key: String, fallback: Long): Long = (obj[key] as? JsonPrimitive)?.longOrNull ?: fallback
        fun json(key: String): JsonObject? = obj[key] as? JsonObject
        fun strings(key: String): List<String> =
            (obj[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }

    fun jsString(s: String): String = JsonPrimitive(s).toString()

    fun parse(json: String): Msg {
        val obj = runCatching { lenient.parseToJsonElement(json).jsonObject }.getOrNull()
            ?: return Msg.Unknown("malformed")
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: return Msg.Unknown("notype")
        val f = Fields(obj)
        return parseComposer(type, f)
            ?: parseSettings(type, f)
            ?: parseGuard(type, f)
            ?: parseRequestCards(type, f)
            ?: parseDiffs(type, f)
            ?: parseAttachments(type, f)
            ?: parseSessionControls(type, f)
            ?: Msg.Unknown(type)
    }

    private fun parseComposer(type: String, f: Fields): Msg? = when (type) {
        "send" -> Msg.Send(f.text("text"), f.text("scope"))
        "interrupt" -> Msg.Interrupt(f.text("scope"))
        "cycleMode" -> Msg.CycleMode
        "ready" -> Msg.Ready
        "diag" -> Msg.Diagnostics(f.text("report"))
        "copy" -> Msg.Copy(f.text("text"))
        "removeQueued" -> Msg.RemoveQueued(f.int("index", -1))
        else -> null
    }

    private fun parseSettings(type: String, f: Fields): Msg? = when (type) {
        "changeModel" -> Msg.ChangeModel(f.str("value"))
        "changeMode" -> Msg.ChangeMode(f.text("wire"))
        "changeEffort" -> Msg.ChangeEffort(f.str("value"))
        "changeThinking" -> Msg.ChangeThinking(f.bool("on"))
        "changeVibe" -> Msg.ChangeVibe(f.bool("on"))
        "changeProvider" -> Msg.ChangeProvider(f.text("id"))
        "settingsToggle" -> Msg.SettingsToggle(f.text("key"), f.bool("on"))
        "settingsRefresh" -> Msg.SettingsRefresh
        "openSettings" -> Msg.OpenSettings
        else -> null
    }

    private fun parseGuard(type: String, f: Fields): Msg? = when (type) {
        "guardSuspend" -> Msg.GuardSuspend(f.text("rule"), f.text("duration"))
        "guardMaster" -> Msg.GuardMaster(f.bool("on"), f.text("duration"))
        "guardWhitelist" -> Msg.GuardWhitelist(f.text("rule"), f.text("command"))
        "guardRevokeApproval" -> Msg.GuardRevokeApproval(f.text("rule"), f.text("command"))
        "guardRemoveWhitelist" -> Msg.GuardRemoveWhitelist(f.text("rule"), f.text("command"))
        "guardAllowAlways" -> Msg.GuardAllowAlways(f.text("id"), f.text("scope"))
        "guardLog" -> Msg.GuardLog
        "guardExplain" -> Msg.GuardExplain(f.text("id"))
        else -> null
    }

    private fun parseRequestCards(type: String, f: Fields): Msg? = when (type) {
        "resolvePermission" -> Msg.ResolvePermission(f.text("id"), f.bool("allow"), f.text("scope"))

        "resolveQuestion" -> Msg.ResolveQuestion(
            f.text("id"),
            f.json("answers").orEmptyAnswers(),
            f.text("scope"),
        )

        "resolveElicitation" -> Msg.ResolveElicitation(
            f.text("id"),
            f.text("action"),
            f.json("content"),
            f.text("scope"),
        )

        "alwaysAllow" -> Msg.AlwaysAllow(f.text("tool"), f.text("id"), f.text("scope"))

        else -> null
    }

    private fun parseDiffs(type: String, f: Fields): Msg? = when (type) {
        "viewDiff" -> Msg.ViewDiff(f.text("id"), f.text("scope"))

        "viewDiffByTool" -> Msg.ViewDiffByTool(f.text("toolUseId"))

        "revertEdit" -> Msg.RevertEdit(f.text("toolUseId"))

        "open" -> Msg.Open(f.text("url"))

        "resolveLinks" -> Msg.ResolveLinks(
            f.long("rowId", -1L),
            strList(f.obj["paths"]),
            strList(f.obj["symbols"]),
        )

        else -> null
    }

    private fun parseAttachments(type: String, f: Fields): Msg? = when (type) {
        "removeAttachment" -> Msg.RemoveAttachment(f.text("id"))
        "requestAttachData" -> Msg.RequestAttachData
        "attachPath" -> Msg.AttachPath(f.text("path"))
        "attachSelection" -> Msg.AttachSelection
        "attachCurrentFile" -> Msg.AttachCurrentFile
        "pasteClipboardImage" -> Msg.PasteClipboardImage(f.bool("notify"))
        "pasteClipboard" -> Msg.PasteClipboard
        "attach" -> Msg.Attach(f.text("name"), f.text("mediaType"), f.text("base64"))
        else -> parseProjectTree(type, f)
    }

    private fun parseProjectTree(type: String, f: Fields): Msg? = when (type) {
        "treeChildren" -> Msg.TreeChildren(f.text("path"), f.text("mode"))
        "treeExpand" -> Msg.TreeExpand(f.text("path"), f.text("mode"))
        "attachPaths" -> Msg.AttachPaths(strList(f.obj["paths"]))
        else -> null
    }

    private fun parseSessionControls(type: String, f: Fields): Msg? = when (type) {
        "mcpReconnect" -> Msg.McpReconnect(f.text("name"))

        "mcpToggle" -> Msg.McpToggle(f.text("name"), f.bool("enabled"))

        "stopTask" -> Msg.StopTask(f.text("taskId"))

        "setWorkloadWindow" -> Msg.SetWorkloadWindow(f.int("minutes", -1))

        "installClaude" -> Msg.InstallClaude(f.text("method"))

        "setBinaryPath" -> Msg.SetBinaryPath(f.text("path"))

        "recheckBinary" -> Msg.RecheckBinary

        else -> parseGitControls(type, f)
            ?: parseVulnControls(type, f)
            ?: parseTabControls(type, f)
            ?: parseAuthControls(type, f)
    }

    private fun parseVulnControls(type: String, f: Fields): Msg? = when (type) {
        "openVulnView" -> Msg.OpenVulnView
        "vulnConsent" -> Msg.VulnConsentChoice(f.bool("granted"))
        "vulnScan" -> Msg.VulnScan
        "vulnCancel" -> Msg.VulnCancel
        "vulnInventory" -> Msg.VulnInventoryRequest
        "vulnFix" -> Msg.VulnFix(f.text("findingId"))
        "vulnPlan" -> Msg.VulnPlan(f.strings("tiers"))
        else -> null
    }

    private fun parseGitControls(type: String, f: Fields): Msg? = when (type) {
        "gitAction" -> Msg.GitAction(f.text("id"), f.text("hash"))
        "openGitView" -> Msg.OpenGitView
        "newChat" -> Msg.NewChat
        "closeThisChat" -> Msg.CloseThisChat
        else -> null
    }

    private fun parseTabControls(type: String, f: Fields): Msg? = when (type) {
        "revealAgent" -> Msg.RevealAgent(f.text("agentId"), f.text("toolUseId"), f.text("chatId"))
        "revealBackgroundTask" -> Msg.RevealBackgroundTask(f.text("taskId"), f.text("chatId"))
        "showChatTranscript" -> Msg.ShowChatTranscript
        "selectChat" -> Msg.SelectChat(f.text("chatId"))
        "closeChat" -> Msg.CloseChat(f.text("chatId"))
        "selectAgent" -> Msg.SelectAgent(f.text("agentId"))
        "closeAgent" -> Msg.CloseAgent(f.text("agentId"))
        else -> null
    }

    private fun parseAuthControls(type: String, f: Fields): Msg? = when (type) {
        "loginSubscription" -> Msg.LoginSubscription
        "loginConsole" -> Msg.LoginConsole
        "useApiKey" -> Msg.UseApiKey(f.text("key"))
        "submitLoginCode" -> Msg.SubmitLoginCode(f.text("code"))
        "cancelLogin" -> Msg.CancelLogin
        "dismissAuth" -> Msg.DismissAuth
        "logout" -> Msg.Logout
        else -> null
    }

    private fun JsonObject?.orEmptyAnswers(): Map<String, String> =
        this?.entries?.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
        }?.toMap().orEmpty()

    private fun strList(el: kotlinx.serialization.json.JsonElement?): List<String> =
        (el as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.filter { it.isNotBlank() }.orEmpty()
}
