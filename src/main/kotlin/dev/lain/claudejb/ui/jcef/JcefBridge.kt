package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.permission.ElicitationCard
import dev.lain.claudejb.permission.PendingPermission
import dev.lain.claudejb.protocol.AskQuestion
import dev.lain.claudejb.session.EntryDTO
import dev.lain.claudejb.session.TranscriptEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The **pure** half of the JCEF bridge: it serializes backend data to the JSON the web frontend consumes
 * (`window.cc.*`) and parses the frontend's messages (`window.__ccSend`) back into a typed [Msg]. It holds no
 * IDE/browser state, so it is fully unit-testable without a live Chromium (the browser plumbing lives in
 * [JcefHost]). All escaping is handled by kotlinx-serialization's [JsonObject.toString], so arbitrary model
 * text crosses the boundary safely.
 */
object JcefBridge {

    private val lenient = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ── Kotlin → JS : serialization ────────────────────────────────────────────────────────────────────

    /**
     * One transcript row as the frontend's entry shape:
     * `{id, order, speaker, text, meta?, toolUseId?, parent?, state, elapsed}`. [order] is the row's current
     * index in the transcript; the frontend upserts by `id` and repositions the row to `order`, so a coalesced
     * batch can carry just the changed rows yet still land them in the right place.
     */
    fun entryJson(e: TranscriptEntry, order: Int): JsonObject = buildJsonObject {
        put("id", e.id)
        put("order", order)
        put("speaker", e.speaker.name)
        put("text", e.text)
        e.meta?.let { put("meta", it) }
        // A label that only became knowable after the row existed — an Agent card gaining the description of
        // what its agent is actually doing. The frontend prefers it over `meta`, which stays the tool's name.
        e.toolTitle?.let { put("title", it) }
        e.toolUseId?.let { put("toolUseId", it) }
        e.parentToolUseId?.let { put("parent", it) }
        // Project-relative file for a file tool → the frontend renders the card label as a jump-to-code link.
        e.filePath?.let { put("filePath", it) }
        // The raw command text for a command call (Bash, PowerShell, MCP…) → the frontend renders it as its
        // own copyable code block in the tool card, instead of plain text in the collapsed header.
        e.commandText?.let { put("command", it) }
        put("state", e.toolState.name)
        put("elapsed", e.elapsedSeconds)
        // A completed Edit/Write/MultiEdit card is reviewable: the frontend shows a "View diff"
        // button that opens the native diff from the captured pre-write snapshot (by tool_use_id).
        if (e.speaker.name == "TOOL" && e.toolUseId != null && e.meta in REVIEWABLE_TOOLS) {
            put("reviewable", true)
        }
    }

    /** Tools whose edits we can reconstruct a diff for — mirrors `DiffPresenter.REVIEWABLE_TOOLS`. */
    private val REVIEWABLE_TOOLS = setOf("Edit", "Write", "MultiEdit")

    /** A batch of `(row, order)` for one `cc.batch([...])` frame (the JS upserts each by id). JSON array literal. */
    fun batchJson(items: List<Pair<TranscriptEntry, Int>>): String =
        JsonArray(items.map { (e, order) -> entryJson(e, order) }).toString()

    /**
     * The same row shape, built from a **reconstructed** entry rather than a live one.
     *
     * An agent's transcript is read back from the binary's own per-agent file (as is a restored session's),
     * so it arrives as [dev.lain.claudejb.session.EntryDTO] with no live tool state and no row ids. Ids are
     * synthesised from the position, which is all the frontend needs — it upserts by id and repositions to
     * `order`, and a reconstructed transcript is replaced wholesale rather than patched row by row.
     *
     * Tool rows are marked FINISHED: whatever the agent was doing when it wrote that file, it is not doing
     * it now in a way this row can track, and a card left spinning forever is a lie the UI tells by omission.
     */
    fun agentBatchJson(
        entries: List<EntryDTO>,
        titles: Map<String, String> = emptyMap(),
        running: Set<String> = emptySet(),
        expanded: Boolean = false,
        /** Whether the agent this transcript belongs to is still working — see the `inFlight` branch below. */
        ownerRunning: Boolean = false,
    ): String =
        JsonArray(
            entries.mapIndexed { index, dto ->
                buildJsonObject {
                    put("id", index.toLong())
                    put("order", index)
                    put("speaker", dto.speaker)
                    put("text", dto.text)
                    dto.meta?.let { put("meta", it) }
                    // The same label the live path gives an Agent card, so a card inside an agent's own
                    // transcript reads `Agent (Inventory of dependencies)` too — it was only ever applied to
                    // the chat's rows, which is exactly where the user does NOT need it most.
                    dto.toolUseId?.let { id -> titles[id]?.let { put("title", it) } }
                    dto.toolUseId?.let { put("toolUseId", it) }
                    dto.filePath?.let { put("filePath", it) }
                    dto.commandText?.let { put("command", it) }
                    put("state", agentRowState(dto, running, ownerRunning))
                    // A background task's view is nothing BUT its command and its output: shipping it
                    // collapsed means the one thing you opened the tab for is behind a click.
                    if (expanded) put("open", true)
                    put("elapsed", 0)
                    if (dto.speaker == "TOOL" && dto.toolUseId != null && dto.meta in REVIEWABLE_TOOLS) {
                        put("reviewable", true)
                    }
                }
            },
        ).toString()

    /**
     * RUNNING when the call has no result yet ([EntryDTO.inFlight]) or when it IS an agent that is still
     * working. Marking everything FINISHED is what made a live card sit there green and still: a Bash the
     * agent was running right now looked exactly like one that had ended half an hour ago.
     */
    private fun agentRowState(dto: EntryDTO, running: Set<String>, ownerRunning: Boolean): String = when {
        // A failure outranks everything: it is the one state you must not miss.
        dto.failed -> "ERROR"

        dto.toolUseId in running -> "RUNNING"

        // A call with no result is only IN FLIGHT while something could still return it. In a transcript
        // whose owner has stopped, it was cut off — cancelled, not running.
        dto.inFlight -> if (ownerRunning) "RUNNING" else "ERROR"

        else -> "FINISHED"
    }

    /** One pending permission as a card the frontend renders (Accept/Reject/View-diff, plan, or AskUserQuestion). */
    fun permissionJson(p: PendingPermission, diff: String? = null): JsonObject = buildJsonObject {
        put("id", p.requestId)
        put("tool", p.toolName)
        put("title", p.title)
        put("summary", p.summary)
        put("headline", p.headline)
        put("reviewable", p.reviewable)
        put("isPlan", p.isPlan)
        p.planText?.let { put("planText", it) }
        p.description?.let { put("description", it) }
        p.decisionReason?.let { put("decisionReason", it) }
        p.blockedPath?.let { put("blockedPath", it) }
        // A read-only unified diff for reviewable edits, so the card shows what's changing (red/green) — edits are
        // accepted/rejected as a whole; there is no per-line selection (that produced incoherent, broken code).
        diff?.takeIf { it.isNotBlank() }?.let { put("diff", it) }
        // The two card shapes that carry their own nested payload; each is only present on its own kind of card.
        p.questions?.let { put("questions", questionsJson(it)) }
        p.elicitation?.let { put("elicitation", elicitationJson(it)) }
    }

    /** The AskUserQuestion payload: questions, each with its options. */
    private fun questionsJson(questions: List<AskQuestion>) = buildJsonArray {
        questions.forEach { q ->
            add(
                buildJsonObject {
                    put("question", q.question)
                    put("header", q.header)
                    put("multiSelect", q.multiSelect)
                    put(
                        "options",
                        buildJsonArray {
                            q.options.forEach { o ->
                                add(
                                    buildJsonObject {
                                        put("label", o.label)
                                        put("description", o.description)
                                        o.preview?.let { put("preview", it) }
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }
    }

    /** The MCP elicitation payload: the server's ask, plus the form fields derived from its schema. */
    private fun elicitationJson(e: ElicitationCard) = buildJsonObject {
        put("serverName", e.serverName)
        put("message", e.message)
        e.description?.let { put("description", it) }
        e.mode?.let { put("mode", it) }
        e.url?.let { put("url", it) }
        put(
            "fields",
            buildJsonArray {
                e.fields.forEach { f ->
                    add(
                        buildJsonObject {
                            put("name", f.name)
                            put("type", f.type)
                            put("title", f.title)
                            put("required", f.required)
                        },
                    )
                }
            },
        )
    }

    fun permissionsJson(list: List<PendingPermission>, diffByRequest: Map<String, String> = emptyMap()): String =
        JsonArray(list.map { permissionJson(it, diffByRequest[it.requestId]) }).toString()

    // ── JS → Kotlin : parsing ──────────────────────────────────────────────────────────────────────────

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

        data class Send(val text: String) : Prompting
        object Interrupt : Prompting
        object CycleMode : Prompting
        data class RemoveQueued(val index: Int) : Prompting
        data class Copy(val text: String) : Prompting

        object Ready : Lifecycle
        object OpenPalette : Lifecycle

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

        data class ResolvePermission(val id: String, val allow: Boolean) : RequestCard
        data class ResolveQuestion(val id: String, val answers: Map<String, String>) : RequestCard
        data class ResolveElicitation(val id: String, val action: String, val content: JsonObject?) : RequestCard
        data class AlwaysAllow(val tool: String, val id: String) : RequestCard

        data class ViewDiff(val id: String) : Diffs
        data class ViewDiffByTool(val toolUseId: String) : Diffs
        data class RevertEdit(val toolUseId: String) : Diffs
        object OpenDiffHistory : Diffs
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

    private fun parseComposer(type: String, f: Fields): Msg? = when (type) {
        "send" -> Msg.Send(f.text("text"))
        "interrupt" -> Msg.Interrupt
        "cycleMode" -> Msg.CycleMode
        "ready" -> Msg.Ready
        "palette" -> Msg.OpenPalette
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
        else -> null
    }

    private fun parseRequestCards(type: String, f: Fields): Msg? = when (type) {
        "resolvePermission" -> Msg.ResolvePermission(f.text("id"), f.bool("allow"))
        "resolveQuestion" -> Msg.ResolveQuestion(f.text("id"), f.json("answers").orEmptyAnswers())
        "resolveElicitation" -> Msg.ResolveElicitation(f.text("id"), f.text("action"), f.json("content"))
        "alwaysAllow" -> Msg.AlwaysAllow(f.text("tool"), f.text("id"))
        else -> null
    }

    private fun parseDiffs(type: String, f: Fields): Msg? = when (type) {
        "viewDiff" -> Msg.ViewDiff(f.text("id"))

        "viewDiffByTool" -> Msg.ViewDiffByTool(f.text("toolUseId"))

        "revertEdit" -> Msg.RevertEdit(f.text("toolUseId"))

        "openDiffHistory" -> Msg.OpenDiffHistory

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

        // The "Claude Code was not found" boot card.
        "installClaude" -> Msg.InstallClaude(f.text("method"))

        "setBinaryPath" -> Msg.SetBinaryPath(f.text("path"))

        "recheckBinary" -> Msg.RecheckBinary

        else -> parseTabControls(type, f) ?: parseAuthControls(type, f)
    }

    /** The tab bar and the Workloads view (app-tabs.js / app-session.js). Split out for complexity only. */
    private fun parseTabControls(type: String, f: Fields): Msg? = when (type) {
        "revealAgent" -> Msg.RevealAgent(f.text("agentId"), f.text("toolUseId"), f.text("chatId"))
        "revealBackgroundTask" -> Msg.RevealBackgroundTask(f.text("taskId"), f.text("chatId"))
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

    /**
     * The answer to a `resolveLinks` request: `{ rowId, links:[{ token, path, line? }] }`. Only tokens the host
     * could actually resolve appear — the frontend links exactly those and leaves the rest as plain text.
     */
    fun linksJson(rowId: Long, resolved: List<dev.lain.claudejb.ui.LinkResolver.Resolved>): String =
        buildJsonObject {
            put("rowId", rowId)
            put(
                "links",
                buildJsonArray {
                    resolved.forEach { r ->
                        add(
                            buildJsonObject {
                                put("token", r.token)
                                put("path", r.path)
                                r.line?.let { put("line", it) }
                            },
                        )
                    }
                },
            )
        }.toString()
}
