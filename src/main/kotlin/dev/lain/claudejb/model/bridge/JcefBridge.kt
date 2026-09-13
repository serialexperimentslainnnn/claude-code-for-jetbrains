package dev.lain.claudejb.model.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

    private class Fields(val obj: JsonObject) {
        fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.contentOrNull
        fun text(key: String): String = str(key).orEmpty()
        fun bool(key: String): Boolean = (obj[key] as? JsonPrimitive)?.booleanOrNull ?: false
        fun int(key: String, fallback: Int): Int = (obj[key] as? JsonPrimitive)?.intOrNull ?: fallback
        fun long(key: String, fallback: Long): Long = (obj[key] as? JsonPrimitive)?.longOrNull ?: fallback
        fun json(key: String): JsonObject? = obj[key] as? JsonObject
        fun strings(key: String): List<String> = strList(obj[key])
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
            ?: parseLog(type, f)
            ?: parseSessionControls(type, f)
            ?: Msg.Unknown(type)
    }

    private fun parseLog(type: String, f: Fields): Msg? = when (type) {
        "logLines" -> Msg.LogLines(f.long("since", -1L))
        "logDebug" -> Msg.LogDebug(f.bool("on"))
        "logCopy" -> Msg.LogCopy(f.text("level"))
        else -> null
    }

    private fun parseComposer(type: String, f: Fields): Msg? = when (type) {
        "send" -> Msg.Send(f.text("text"), f.text("scope"))
        "interrupt" -> Msg.Interrupt(f.text("scope"))
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
        "resolveQuestion" -> Msg.ResolveQuestion(f.text("id"), f.json("answers").orEmptyAnswers(), f.text("scope"))
        "resolveElicitation" -> Msg.ResolveElicitation(f.text("id"), f.text("action"), f.json("content"), f.text("scope"))
        "alwaysAllow" -> Msg.AlwaysAllow(f.text("tool"), f.text("id"), f.text("scope"))
        else -> null
    }

    private fun parseDiffs(type: String, f: Fields): Msg? = when (type) {
        "viewDiff" -> Msg.ViewDiff(f.text("id"), f.text("scope"))
        "viewDiffByTool" -> Msg.ViewDiffByTool(f.text("toolUseId"))
        "revertEdit" -> Msg.RevertEdit(f.text("toolUseId"))
        "open" -> Msg.Open(f.text("url"))
        "resolveLinks" -> Msg.ResolveLinks(f.long("rowId", -1L), f.strings("paths"), f.strings("symbols"))
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
        "treeChildren" -> Msg.TreeChildren(f.text("path"), f.text("mode"))
        "treeExpand" -> Msg.TreeExpand(f.text("path"), f.text("mode"))
        "attachPaths" -> Msg.AttachPaths(f.strings("paths"))
        else -> null
    }

    private fun parseSessionControls(type: String, f: Fields): Msg? = when (type) {
        "mcpRefresh" -> Msg.McpRefresh
        "mcpReconnect" -> Msg.McpReconnect(f.text("name"))
        "mcpToggle" -> Msg.McpToggle(f.text("name"), f.bool("enabled"))
        "stopTask" -> Msg.StopTask(f.text("taskId"))
        "setWorkloadWindow" -> Msg.SetWorkloadWindow(f.int("minutes", -1))
        "gitAction" -> Msg.GitAction(f.text("id"), f.text("hash"))
        "openGitView" -> Msg.OpenGitView
        "newChat" -> Msg.NewChat
        "closeThisChat" -> Msg.CloseThisChat
        else -> parseVuln(type, f) ?: parseNavigation(type, f) ?: parseOnboarding(type, f)
    }

    private fun parseVuln(type: String, f: Fields): Msg? = when (type) {
        "openVulnView" -> Msg.OpenVulnView
        "vulnConsent" -> Msg.VulnConsentChoice(f.bool("granted"))
        "vulnScan" -> Msg.VulnScan
        "vulnCancel" -> Msg.VulnCancel
        "vulnInventory" -> Msg.VulnInventoryRequest
        "vulnFix" -> Msg.VulnFix(f.text("findingId"))
        "vulnPlan" -> Msg.VulnPlan(f.strings("tiers"))
        else -> null
    }

    private fun parseNavigation(type: String, f: Fields): Msg? = when (type) {
        "revealAgent" -> Msg.RevealAgent(f.text("agentId"), f.text("toolUseId"), f.text("chatId"))
        "revealBackgroundTask" -> Msg.RevealBackgroundTask(f.text("taskId"), f.text("chatId"))
        "showChatTranscript" -> Msg.ShowChatTranscript
        "selectChat" -> Msg.SelectChat(f.text("chatId"))
        "closeChat" -> Msg.CloseChat(f.text("chatId"))
        "selectAgent" -> Msg.SelectAgent(f.text("agentId"))
        "closeAgent" -> Msg.CloseAgent(f.text("agentId"))
        else -> null
    }

    private fun parseOnboarding(type: String, f: Fields): Msg? = when (type) {
        "installClaude" -> Msg.InstallClaude(f.text("method"))
        "setBinaryPath" -> Msg.SetBinaryPath(f.text("path"))
        "recheckBinary" -> Msg.RecheckBinary
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
        this?.entries?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()

    private fun strList(el: JsonElement?): List<String> =
        (el as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.filter { it.isNotBlank() }.orEmpty()
}
