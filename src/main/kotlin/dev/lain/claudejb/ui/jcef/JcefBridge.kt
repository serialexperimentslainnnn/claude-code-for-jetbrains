package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.permission.PendingPermission
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

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

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
        p.questions?.let { qs ->
            put("questions", buildJsonArray {
                qs.forEach { q ->
                    add(buildJsonObject {
                        put("question", q.question)
                        put("header", q.header)
                        put("multiSelect", q.multiSelect)
                        put("options", buildJsonArray {
                            q.options.forEach { o ->
                                add(buildJsonObject {
                                    put("label", o.label)
                                    put("description", o.description)
                                    o.preview?.let { put("preview", it) }
                                })
                            }
                        })
                    })
                }
            })
        }
        p.elicitation?.let { e ->
            put("elicitation", buildJsonObject {
                put("serverName", e.serverName)
                put("message", e.message)
                e.description?.let { put("description", it) }
                e.mode?.let { put("mode", it) }
                e.url?.let { put("url", it) }
                put("fields", buildJsonArray {
                    e.fields.forEach { f ->
                        add(buildJsonObject {
                            put("name", f.name)
                            put("type", f.type)
                            put("title", f.title)
                            put("required", f.required)
                        })
                    }
                })
            })
        }
    }

    fun permissionsJson(list: List<PendingPermission>, diffByRequest: Map<String, String> = emptyMap()): String =
        JsonArray(list.map { permissionJson(it, diffByRequest[it.requestId]) }).toString()

    // ── JS → Kotlin : parsing ──────────────────────────────────────────────────────────────────────────

    /** A typed message from the web frontend. [Unknown] keeps the dispatcher total without throwing. */
    sealed interface Msg {
        data class Send(val text: String) : Msg
        object Interrupt : Msg
        object CycleMode : Msg
        object Ready : Msg
        object OpenPalette : Msg
        data class ChangeModel(val value: String?) : Msg
        data class ChangeMode(val wire: String) : Msg
        data class ChangeEffort(val value: String?) : Msg
        data class ChangeThinking(val on: Boolean) : Msg
        data class ChangeVibe(val on: Boolean) : Msg
        data class ChangeProvider(val id: String) : Msg
        data class RemoveQueued(val index: Int) : Msg
        data class ResolvePermission(val id: String, val allow: Boolean) : Msg
        data class ResolveQuestion(val id: String, val answers: Map<String, String>) : Msg
        data class ResolveElicitation(val id: String, val action: String, val content: JsonObject?) : Msg
        data class AlwaysAllow(val tool: String, val id: String) : Msg
        data class ViewDiff(val id: String) : Msg
        data class ViewDiffByTool(val toolUseId: String) : Msg
        data class RevertEdit(val toolUseId: String) : Msg
        object OpenDiffHistory : Msg
        data class Open(val url: String) : Msg

        /**
         * The transcript detected jump-to-code candidates in a settled row and asks the host which are real. Only
         * the resolved ones become links, so a path that doesn't exist (or a word that isn't a symbol) never turns
         * into a dead hyperlink. Answered with `cc.links({ rowId, links: [...] })`.
         */
        data class ResolveLinks(val rowId: Long, val paths: List<String>, val symbols: List<String>) : Msg
        data class Copy(val text: String) : Msg
        // Stage 2 — attachments (composer chips, drag/drop/paste, file picker).
        data class RemoveAttachment(val id: String) : Msg
        object PickFiles : Msg
        object PickDirectory : Msg
        object RequestAttachData : Msg
        data class AttachPath(val path: String) : Msg
        object AttachSelection : Msg
        object AttachCurrentFile : Msg
        data class PasteClipboardImage(val notify: Boolean) : Msg
        object PasteClipboard : Msg  // Ctrl+V: host reads text OR image from the system clipboard
        data class Attach(val name: String, val mediaType: String, val base64: String) : Msg
        // Stage 3 — session dashboard (MCP health + subagent control).
        data class McpReconnect(val name: String) : Msg
        data class McpToggle(val name: String, val enabled: Boolean) : Msg
        data class StopTask(val taskId: String) : Msg
        data class Unknown(val type: String) : Msg
    }

    /** Parses one `window.__ccSend` payload. Malformed input or an unrecognized `type` maps to [Msg.Unknown]. */
    fun parse(json: String): Msg {
        val obj = runCatching { lenient.parseToJsonElement(json).jsonObject }.getOrNull()
            ?: return Msg.Unknown("malformed")
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return Msg.Unknown("notype")
        fun str(key: String): String? = obj[key]?.jsonPrimitive?.contentOrNull
        fun bool(key: String): Boolean = obj[key]?.jsonPrimitive?.booleanOrNull ?: false
        return when (type) {
            "send" -> Msg.Send(str("text").orEmpty())
            "interrupt" -> Msg.Interrupt
            "cycleMode" -> Msg.CycleMode
            "ready" -> Msg.Ready
            "palette" -> Msg.OpenPalette
            "changeModel" -> Msg.ChangeModel(str("value"))
            "changeMode" -> Msg.ChangeMode(str("wire").orEmpty())
            "changeEffort" -> Msg.ChangeEffort(str("value"))
            "changeThinking" -> Msg.ChangeThinking(bool("on"))
            "changeVibe" -> Msg.ChangeVibe(bool("on"))
            "changeProvider" -> Msg.ChangeProvider(str("id").orEmpty())
            "removeQueued" -> Msg.RemoveQueued(obj["index"]?.jsonPrimitive?.intOrNull ?: -1)
            "resolvePermission" -> Msg.ResolvePermission(str("id").orEmpty(), bool("allow"))
            "resolveQuestion" -> {
                val answers = (obj["answers"] as? JsonObject).orEmptyAnswers()
                Msg.ResolveQuestion(str("id").orEmpty(), answers)
            }
            "resolveElicitation" -> Msg.ResolveElicitation(str("id").orEmpty(), str("action").orEmpty(), obj["content"] as? JsonObject)
            "alwaysAllow" -> Msg.AlwaysAllow(str("tool").orEmpty(), str("id").orEmpty())
            "viewDiff" -> Msg.ViewDiff(str("id").orEmpty())
            "viewDiffByTool" -> Msg.ViewDiffByTool(str("toolUseId").orEmpty())
            "revertEdit" -> Msg.RevertEdit(str("toolUseId").orEmpty())
            "openDiffHistory" -> Msg.OpenDiffHistory
            "open" -> Msg.Open(str("url").orEmpty())
            "resolveLinks" -> Msg.ResolveLinks(
                (obj["rowId"] as? JsonPrimitive)?.longOrNull ?: -1L,
                strList(obj["paths"]),
                strList(obj["symbols"]),
            )
            "copy" -> Msg.Copy(str("text").orEmpty())
            "removeAttachment" -> Msg.RemoveAttachment(str("id").orEmpty())
            "pickFiles" -> Msg.PickFiles
            "pickDirectory" -> Msg.PickDirectory
            "requestAttachData" -> Msg.RequestAttachData
            "attachPath" -> Msg.AttachPath(str("path").orEmpty())
            "attachSelection" -> Msg.AttachSelection
            "attachCurrentFile" -> Msg.AttachCurrentFile
            "pasteClipboardImage" -> Msg.PasteClipboardImage(bool("notify"))
            "pasteClipboard" -> Msg.PasteClipboard
            "attach" -> Msg.Attach(str("name").orEmpty(), str("mediaType").orEmpty(), str("base64").orEmpty())
            "mcpReconnect" -> Msg.McpReconnect(str("name").orEmpty())
            "mcpToggle" -> Msg.McpToggle(str("name").orEmpty(), bool("enabled"))
            "stopTask" -> Msg.StopTask(str("taskId").orEmpty())
            else -> Msg.Unknown(type)
        }
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
            put("links", buildJsonArray {
                resolved.forEach { r ->
                    add(buildJsonObject {
                        put("token", r.token)
                        put("path", r.path)
                        r.line?.let { put("line", it) }
                    })
                }
            })
        }.toString()
}
