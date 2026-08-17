package dev.lain.claudejb.ui.jcef

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The **inbound pure half** of the JCEF bridge: it parses the frontend's messages (`window.__ccSend`) into a
 * typed [Msg]. It holds no IDE/browser state, so it is fully unit-testable without a live Chromium (the
 * browser plumbing lives in [JcefHost]).
 *
 * The traffic the other way — the JSON the frontend consumes through `window.cc.*` — is built by the outbound
 * payload builders, split by subject and equally pure: [JcefTranscriptPayload] (rows, agent transcripts,
 * jump-to-code links), [JcefCardPayload] (permission / question / elicitation cards), [JcefState] (composer),
 * [JcefSessionData] (dashboard) and [JcefTabsData] (tab bar).
 *
 * [jsString] stays here because it belongs to neither direction's payload: it is the escaping primitive of the
 * boundary itself, used wherever a host-side caller embeds a value in a `host.exec` call.
 */
object JcefBridge {

    private val lenient = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * The scope naming the conversation embedded in the Git view, when a request card belongs to it.
     *
     * Two sessions draw permission cards into the same page now: the chat this browser was built for, and
     * the Git one ([Msg.GitChatSend]). They share ONE renderer — a card is where a `git push` is shown before
     * it runs, so a second implementation would be a second place for that to be displayed wrongly — so the
     * card carries which session answers it instead of the host inferring. Inferring would mean matching the
     * request id against both and resolving whichever answered first, which on a collision approves a
     * command in the wrong conversation with nothing on screen to say so.
     *
     * An absent scope is the panel's own session, so nothing about the ordinary path changed.
     */
    const val SCOPE_GIT = "git"

    /**
     * A typed message from the web frontend. [Unknown] keeps the dispatcher total without throwing.
     *
     * Grouped into sub-interfaces for the same reason [dev.lain.claudejb.protocol.ClaudeEvent] is: the panel
     * dispatches in two levels instead of one 40-arm `when`, and the compiler still checks exhaustiveness at
     * both, so a new message type cannot be added and then silently ignored. The groups mirror the `parseX`
     * helpers below one-for-one, so a message is parsed and handled by the same concern.
     */
    sealed interface Msg {

        /** Driving a turn from the composer. */
        sealed interface Prompting : Msg

        /** Changing a launch/runtime setting (model, mode, effort, thinking, provider, vibe). */
        sealed interface Settings : Msg

        /** Answering one of the request cards (permission, question, elicitation). */
        sealed interface RequestCard : Msg

        /** Diff review, rollback and jump-to-code. */
        sealed interface Diffs : Msg

        /** Composer attachments: chips, drag/drop/paste, file picker. */
        sealed interface Attachments : Msg

        /** Session dashboard: MCP health and subagent control. */
        sealed interface SessionControl : Msg

        /** Web-app lifecycle and anything the host does not act on. */
        sealed interface Lifecycle : Msg

        /**
         * A turn from the composer — and [scope] says which conversation it is for.
         *
         * There is ONE composer and there are two conversations it can be talking to: this chat, and the one
         * embedded in the Git view while that view is showing it. Tagging the turn is what let the Git chat
         * be a real chat without a second text box inside the panel — which is what it had for an hour, and
         * it was a second thing to style, to keep in sync and to get subtly wrong.
         *
         * Empty is this panel's own session. See [SCOPE_GIT].
         */
        data class Send(val text: String, val scope: String = "") : Prompting

        data class Interrupt(val scope: String = "") : Prompting
        object CycleMode : Prompting
        data class RemoveQueued(val index: Int) : Prompting
        data class Copy(val text: String) : Prompting

        object Ready : Lifecycle

        /**
         * A one-shot report of what the embedded browser actually resolves at runtime — media queries, CSS
         * feature support, computed styles. The plugin's UI *is* a browser, and until now nothing could see
         * inside it: a rule that silently did not apply looked identical to a backend that never sent the
         * state, and both looked identical to a bug in between. This closes that blind spot.
         */
        data class Diagnostics(val report: String) : Lifecycle

        data class Unknown(val type: String) : Lifecycle

        data class ChangeModel(val value: String?) : Settings
        data class ChangeMode(val wire: String) : Settings
        data class ChangeEffort(val value: String?) : Settings
        data class ChangeThinking(val on: Boolean) : Settings
        data class ChangeVibe(val on: Boolean) : Settings
        data class ChangeProvider(val id: String) : Settings

        /**
         * One switch of the composer's ⚙ menu — the settings worth flipping without leaving the chat.
         *
         * [key] is a CLOSED set, checked against [JcefSettingsMenu.apply]'s `when` and dropped when this
         * build does not know it. Five of the nine are the deterministic guard's own rules, so this is a
         * browser message that can relax a security control: it may only ever downgrade a refusal to a card
         * the user still has to answer, never to silent approval, and the `when` is what makes an unrecognised
         * key a case rather than an assignment.
         */
        data class SettingsToggle(val key: String, val on: Boolean) : Settings

        /** The last row of that menu: the real Settings page, for everything the popup deliberately omits. */
        object OpenSettings : Settings

        /** [scope] names the conversation this card belongs to; empty is the panel's own. See [SCOPE_GIT]. */
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

        /**
         * The transcript detected jump-to-code candidates in a settled row and asks the host which are real. Only
         * the resolved ones become links, so a path that doesn't exist (or a word that isn't a symbol) never turns
         * into a dead hyperlink. Answered with `cc.links({ rowId, links: [...] })`.
         */
        data class ResolveLinks(val rowId: Long, val paths: List<String>, val symbols: List<String>) : Diffs

        data class RemoveAttachment(val id: String) : Attachments
        object PickFiles : Attachments
        object PickDirectory : Attachments
        object RequestAttachData : Attachments
        data class AttachPath(val path: String) : Attachments
        object AttachSelection : Attachments
        object AttachCurrentFile : Attachments
        data class PasteClipboardImage(val notify: Boolean) : Attachments
        object PasteClipboard : Attachments // Ctrl+V: host reads text OR image from the system clipboard
        data class Attach(val name: String, val mediaType: String, val base64: String) : Attachments

        data class McpReconnect(val name: String) : SessionControl
        data class McpToggle(val name: String, val enabled: Boolean) : SessionControl
        data class StopTask(val taskId: String) : SessionControl

        /**
         * The Workloads view's retention control: keep finished work listed for [minutes], `0` meaning all of
         * it for this session.
         *
         * The value is CLOSED even though it is a number: the host checks it against
         * [dev.lain.claudejb.session.WorkloadWindow.WINDOW_MINUTES] and ignores anything else, so the offered
         * set and the applied set stay one set. A window this build does not know would otherwise be stored
         * and then measured against nothing anybody chose, which fails silently — the diagram simply shows
         * the wrong things.
         */
        data class SetWorkloadWindow(val minutes: Int) : SessionControl

        /**
         * A button on the Git view: run the catalogue action with this [id], against this [hash].
         *
         * **ONE message for both bars.** The action bar's buttons and the per-commit buttons of the history
         * rail are the same catalogue with the same executor; a second message type for the second bar
         * (`gitCommitAction`) meant the page could send something the host had no parser for, which is a
         * button that silently does nothing.
         *
         * [hash] is empty for every entry that does not act on a commit, and the host ignores it there —
         * `GitActionCatalog.GitAction.takesCommit` decides, not the presence of a value on the wire.
         *
         * The id is CLOSED and the hash is not, and that is the whole trust story of this message. An id this
         * build does not know is dropped by looking it up in [dev.lain.claudejb.ui.GitActionCatalog]; a hash
         * cannot be checked that way — the point of it is to be a value never seen before — so it is checked
         * for the SHAPE of a Git object name (`GitActionCatalog.isCommitHash`) before it can reach a prompt.
         * Nothing off this wire ever reaches an argument vector.
         */
        data class GitAction(val id: String, val hash: String = "") : SessionControl

        /**
         * The chat's own action buttons, which live on the composer rather than in the tool window's title
         * bar (`app-composer-actions.js`).
         *
         * Three messages and not six: *Stop* and *Log out* already had one ([Interrupt], [Logout]) and reuse
         * it, and *Commands* needs none at all — the palette is the page's own, and the round trip that used
         * to open it existed only because the button was outside the browser.
         */
        object NewChat : SessionControl

        object CloseAllDiffs : SessionControl

        /** Go to the Git view, opening its chat if this project does not have one yet. */
        object OpenGitView : SessionControl

        /**
         * Go to an agent's tab: sent by the Agent/Task card in the transcript and by the dashboard lists.
         *
         * Two ways to name the agent, because the two senders know different things. The dashboard has the
         * [agentId]; a transcript card only ever knew its [toolUseId], and the pairing between them comes
         * from the binary's own sidecar, which the host reads — so the card sends what it has and the host
         * resolves. Exactly one of the two is non-blank.
         *
         * Also the documented way back to a tab the user closed: closing hides a view, it never destroys
         * anything, so revealing it again just re-opens a window onto a file that is still there.
         *
         * [chatId] names the chat the agent belongs to, when the sender knows it.
         *
         * The Workloads diagram spans EVERY chat, but the message arrives at the panel of the one you are
         * looking at — which then searched its own session, found nothing, and went nowhere. That is why
         * clicking a node there did nothing while the same node in the tab bar's popup worked: the popup only
         * ever shows one chat, so the panel that received it was always the right one.
         */
        data class RevealAgent(val agentId: String, val toolUseId: String, val chatId: String = "") :
            SessionControl

        /**
         * Open a background task's own view: what it is, who started it, and whatever output came back.
         *
         * Separate from [RevealAgent] because a task is not an agent and has no transcript. Sending the user
         * to its owner's transcript instead — which is what the dashboard row used to do — either did nothing
         * visible or moved them somewhere unrelated.
         *
         * [chatId] as in [RevealAgent]: Workloads spans every chat, so a task names the one it belongs to.
         */
        data class RevealBackgroundTask(val taskId: String, val chatId: String = "") : SessionControl

        /** Back to the chat's own transcript, from whatever agent or task was on screen. */
        data object ShowChatTranscript : SessionControl

        // The tab bar (app-tabs.js). It is part of the page, not a Swing strip above it, so selecting a chat,
        // an agent or closing either arrives here like every other web→host message.
        data class SelectChat(val chatId: String) : SessionControl
        data class CloseChat(val chatId: String) : SessionControl
        data class SelectAgent(val agentId: String) : SessionControl
        data class CloseAgent(val agentId: String) : SessionControl

        /**
         * Pin the open subtab as a chat tab of its own — one of [agentId] / [taskId] is set.
         *
         * A subtab is a VIEW: one browser painting somebody else's transcript, gone the moment you look at
         * something else. Pinning turns it into a real tab that stays put, which is what you want for the
         * one agent you keep coming back to.
         */
        data class PinSubtab(val agentId: String, val taskId: String) : SessionControl

        // The "Claude Code was not found" boot card: run an official installer in the IDE terminal,
        // validate a user-typed binary path, or re-check after an install finished.
        data class InstallClaude(val method: String) : SessionControl
        data class SetBinaryPath(val path: String) : SessionControl
        object RecheckBinary : SessionControl

        // The sign-in card and the dashboard's account button. The two credential-bearing messages
        // (UseApiKey, SubmitLoginCode) carry SECRETS: they cross the in-memory JCEF bridge only, and their
        // values must never be logged, echoed into state pushes, or appear in any error text.
        object LoginSubscription : SessionControl

        /**
         * Sign in against Anthropic Console (API-usage billing) rather than a personal subscription — the
         * route organisations need: the consent includes `org:create_api_key`, so a corporate account is
         * provisioned by signing in instead of by distributing a pasted key.
         */
        object LoginConsole : SessionControl
        data class UseApiKey(val key: String) : SessionControl
        data class SubmitLoginCode(val code: String) : SessionControl
        object CancelLogin : SessionControl
        object DismissAuth : SessionControl
        object Logout : SessionControl
    }

    /** Typed accessors over one inbound payload, so the per-group parsers below read as plain field reads. */
    private class Fields(val obj: JsonObject) {
        fun str(key: String): String? = obj[key]?.jsonPrimitive?.contentOrNull
        fun text(key: String): String = str(key).orEmpty()
        fun bool(key: String): Boolean = obj[key]?.jsonPrimitive?.booleanOrNull ?: false
        fun int(key: String, fallback: Int): Int = obj[key]?.jsonPrimitive?.intOrNull ?: fallback
        fun long(key: String, fallback: Long): Long = (obj[key] as? JsonPrimitive)?.longOrNull ?: fallback
        fun json(key: String): JsonObject? = obj[key] as? JsonObject
    }

    /**
     * A string as a JS expression: a JSON string literal is a valid JavaScript string literal, and the
     * serializer's escaping (quotes, backslashes, control characters) is exactly what stops a message that
     * happens to contain `")` from breaking out of the `host.exec` call that embeds it.
     */
    fun jsString(s: String): String = JsonPrimitive(s).toString()

    /**
     * Parses one `window.__ccSend` payload. Malformed input or an unrecognized `type` maps to [Msg.Unknown].
     *
     * Dispatch is split by CONCERN across the `parseX` helpers below, each returning null for a type it does
     * not own, rather than one `when` over all 40 message types. Unlike [dev.lain.claudejb.protocol.ClaudeEvent]
     * there is no exhaustiveness to preserve here — the subject is a string off the wire, so an unrecognized
     * value is a normal outcome ([Msg.Unknown]) and not a missing branch.
     */
    fun parse(json: String): Msg {
        val obj = runCatching { lenient.parseToJsonElement(json).jsonObject }.getOrNull()
            ?: return Msg.Unknown("malformed")
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return Msg.Unknown("notype")
        val f = Fields(obj)
        return parseComposer(type, f)
            ?: parseSettings(type, f)
            ?: parseRequestCards(type, f)
            ?: parseDiffs(type, f)
            ?: parseAttachments(type, f)
            ?: parseSessionControls(type, f)
            ?: Msg.Unknown(type)
    }

    /**
     * NB there is no inbound `"palette"`: the palette is opened host→page (the toolbar's Commands button →
     * `JcefChatPanel.showCommandPalette` → `cc.openPalette`), and the page opens its own through
     * `CX.openPalette`. One was parsed here until 5.5.0 and no module ever sent it — see
     * `bridge-inbound.test.js`, which is now the gate for that.
     */
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
        "openSettings" -> Msg.OpenSettings
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
        "pickFiles" -> Msg.PickFiles
        "pickDirectory" -> Msg.PickDirectory
        "requestAttachData" -> Msg.RequestAttachData
        "attachPath" -> Msg.AttachPath(f.text("path"))
        "attachSelection" -> Msg.AttachSelection
        "attachCurrentFile" -> Msg.AttachCurrentFile
        "pasteClipboardImage" -> Msg.PasteClipboardImage(f.bool("notify"))
        "pasteClipboard" -> Msg.PasteClipboard
        "attach" -> Msg.Attach(f.text("name"), f.text("mediaType"), f.text("base64"))
        else -> null
    }

    private fun parseSessionControls(type: String, f: Fields): Msg? = when (type) {
        "mcpReconnect" -> Msg.McpReconnect(f.text("name"))

        "mcpToggle" -> Msg.McpToggle(f.text("name"), f.bool("enabled"))

        "stopTask" -> Msg.StopTask(f.text("taskId"))

        // -1, not 0: 0 is `WorkloadWindow.ALL`, a real choice. A missing value must not read as "show
        // everything for ever" — it reads as no choice at all, and the router drops it.
        "setWorkloadWindow" -> Msg.SetWorkloadWindow(f.int("minutes", -1))

        // The "Claude Code was not found" boot card.
        "installClaude" -> Msg.InstallClaude(f.text("method"))

        "setBinaryPath" -> Msg.SetBinaryPath(f.text("path"))

        "recheckBinary" -> Msg.RecheckBinary

        else -> parseGitControls(type, f) ?: parseTabControls(type, f) ?: parseAuthControls(type, f)
    }

    /**
     * The Git view and the composer's action row (app-session-git.js / app-composer-actions.js).
     *
     * Split out for complexity, like [parseTabControls] — but the grouping is a real one: every message here
     * is answered by `GitIntegration` or by the tool window's own commands, and none of them touches the
     * session the page belongs to.
     */
    private fun parseGitControls(type: String, f: Fields): Msg? = when (type) {
        // `hash` is absent for every button that is not drawn on a commit row; `text` gives "" there, which is
        // exactly what the host treats as "no commit", so an omitted field and an empty one mean one thing.
        "gitAction" -> Msg.GitAction(f.text("id"), f.text("hash"))

        "openGitView" -> Msg.OpenGitView

        "newChat" -> Msg.NewChat

        "closeAllDiffs" -> Msg.CloseAllDiffs

        else -> null
    }

    /** The tab bar and the Workloads view (app-tabs.js / app-session.js). Split out for complexity only. */
    private fun parseTabControls(type: String, f: Fields): Msg? = when (type) {
        "revealAgent" -> Msg.RevealAgent(f.text("agentId"), f.text("toolUseId"), f.text("chatId"))
        "revealBackgroundTask" -> Msg.RevealBackgroundTask(f.text("taskId"), f.text("chatId"))
        "showChatTranscript" -> Msg.ShowChatTranscript
        "selectChat" -> Msg.SelectChat(f.text("chatId"))
        "closeChat" -> Msg.CloseChat(f.text("chatId"))
        "selectAgent" -> Msg.SelectAgent(f.text("agentId"))
        "closeAgent" -> Msg.CloseAgent(f.text("agentId"))
        "pinSubtab" -> Msg.PinSubtab(f.text("agentId"), f.text("taskId"))
        else -> null
    }

    /** The sign-in card and the account buttons. Split out of [parseSessionControls] for complexity only. */
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

    /** A JSON array of strings → a Kotlin list (non-strings and non-arrays are dropped, never thrown on). */
    private fun strList(el: kotlinx.serialization.json.JsonElement?): List<String> =
        (el as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.filter { it.isNotBlank() }.orEmpty()
}
