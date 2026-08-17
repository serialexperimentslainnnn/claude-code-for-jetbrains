package dev.lain.claudejb.session

import com.intellij.openapi.project.Project
import dev.lain.claudejb.permission.ToolInputScanner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

/**
 * A flat transcript entry, decoded from the binary's own JSONL. Mirrors the plugin's transcript model
 * (speaker buckets: USER, ASSISTANT, THINKING, TOOL, TOOL_OUTPUT). No serialization annotations — this is
 * the in-memory shape consumed by the UI when restoring a past session. [parentToolUseId] is always null
 * here (subagent nesting is not reconstructed from the sidecar).
 */
data class EntryDTO(
    val speaker: String,
    val text: String,
    val meta: String? = null,
    val toolUseId: String? = null,
    val parentToolUseId: String? = null,
    /** For a file tool: the file it acts on, project-relative — the transcript's jump-to-code link (see
     *  [ToolNaming.toolFilePath]). Null on every other row, and on any row parsed without a project root. */
    val filePath: String? = null,
    /** For a command-executing tool: the raw command, so a restored card renders the same copyable code block a
     *  live one does (see [dev.lain.claudejb.permission.ToolInputScanner.commandText]). Null on every other row. */
    val commandText: String? = null,
    /** For a call that SENDS text: that text, so a restored card carries it too (see
     *  [dev.lain.claudejb.permission.ToolInputScanner.messageText]). Read here as well as live, deliberately:
     *  a field the live parser fills and this one does not is a row that changes when you restore it or open
     *  an agent's tab, which is exactly the asymmetry behind the duplicated-thinking bug. */
    val messageText: String? = null,
    /**
     * A tool call that has no result yet — it was still in flight when this transcript was read.
     *
     * The binary writes the `tool_use` when the call starts and its `tool_result` when it comes back, so a
     * call with no matching result is simply still running. Without this every reconstructed card was drawn
     * as FINISHED, so a Bash the agent is running RIGHT NOW sat there green and still instead of fading like
     * its live counterpart.
     */
    val inFlight: Boolean = false,
    /**
     * The call came back as an error.
     *
     * The JSONL marks the failure on the RESULT row, not on the call, so a reconstructed card had no way to
     * know: a Bash that exited non-zero was drawn green like any other finished call, and the red header the
     * live transcript gives it never appeared.
     */
    val failed: Boolean = false,
)

/**
 * Lightweight handle to a past session: its id, the binary-issued title, the file mtime (newest-first sort key),
 * and best-effort metadata read from the transcript header — the first user prompt, the git branch the session
 * ran on, and the ISO-8601 creation timestamp. Metadata fields are null when the transcript doesn't carry them.
 */
data class SessionRef(
    val sessionId: String,
    val title: String,
    val lastModified: Long,
    val firstPrompt: String? = null,
    val gitBranch: String? = null,
    val createdAt: String? = null,
)

/**
 * Read-only reconstruction of a past conversation from the `claude` binary's transcript (the single source
 * of truth — see [SessionStore]). Parsing is pure and tolerant: every line is decoded under runCatching and
 * unknown line types (ai-title, mode, queue-operation, last-prompt, attachment, summary…) are skipped.
 * IO is blocking — call [readEntries]/[listSessions] off the EDT.
 */
object SessionTranscriptReader {

    private val JSON = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Conservative default cap for the restore path: reconstruct only the last this-many transcript entries
     * of a very large session, so restoring a long-running conversation doesn't stall on parsing/rendering
     * the whole archive. Callers that want the full transcript pass `maxEntries = null` (the historic default
     * for every existing overload). See [parseEntries].
     */
    const val DEFAULT_RESTORE_CAP: Int = 200

    /**
     * Decoded transcript for [sessionId], or empty if the sidecar is absent/unreadable. With [maxEntries] set,
     * only the last N reconstructed entries are kept (tail), keeping ordering intact and dropping orphan tool
     * outputs whose tool_use call fell outside the window. [maxEntries] = null (default) loads the full transcript.
     */
    fun readEntries(sessionId: String, maxEntries: Int? = null, projectRoot: String? = null): List<EntryDTO> =
        SessionStore.readLines(sessionId)?.let { parseEntries(it, maxEntries, projectRoot) } ?: emptyList()

    /**
     * Maps raw JSONL [lines] to the plugin's transcript model. Pure and unit-testable; never throws — a
     * corrupt/blank line is dropped. See [readEntries] for the IO-backed entry point.
     *
     * When [maxEntries] is non-null and positive, only the last N reconstructed entries are returned (the tail),
     * preserving relative ordering. To keep tool calls/outputs coherent, any TOOL_OUTPUT in the window whose
     * originating TOOL call is not also in the window is dropped (orphan output), rather than reconstructing it
     * without its call. A null (default), zero, or negative [maxEntries] returns every entry.
     */
    fun parseEntries(lines: List<String>, maxEntries: Int? = null, projectRoot: String? = null): List<EntryDTO> {
        val out = ArrayList<EntryDTO>()
        for (line in lines) {
            if (line.isBlank()) continue
            val obj = runCatching { JSON.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            runCatching {
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "user" -> parseUser(obj, out)
                    "assistant" -> parseAssistant(obj, out, projectRoot)
                    else -> Unit
                }
            }
        }
        return capTail(markInFlight(tagCommandOutputs(out)), maxEntries)
    }

    /**
     * Marks every TOOL call that has no TOOL_OUTPUT as still running.
     *
     * The binary writes the `tool_use` when a call starts and its `tool_result` when it returns, so a call
     * with no matching result was in flight at the moment this file was read. Nothing else in the transcript
     * says so, which is why a reconstructed card used to be drawn FINISHED unconditionally: a Bash an agent
     * was running RIGHT NOW came back green and still, while the identical card in the live chat faded.
     *
     * A pass over the finished list for the same reason [tagCommandOutputs] is one — the result arrives in a
     * later message than the call, so at parse time the call's own line cannot know.
     */
    private fun markInFlight(entries: List<EntryDTO>): List<EntryDTO> {
        val answered = HashSet<String>()
        val failed = HashSet<String>()
        for (e in entries) {
            if (e.speaker != "TOOL_OUTPUT") continue
            val id = e.toolUseId ?: continue
            answered += id
            // The failure is recorded on the RESULT ("error" in its meta), so the CALL has to be told.
            if (e.meta != null && e.meta.contains("error")) failed += id
        }
        return entries.map { e ->
            if (e.speaker != "TOOL" || e.toolUseId == null) return@map e
            when {
                e.toolUseId in failed -> e.copy(failed = true)
                e.toolUseId !in answered -> e.copy(inFlight = true)
                else -> e
            }
        }
    }

    /**
     * Adds the `command` tag to every TOOL_OUTPUT whose originating TOOL call executed a command, mirroring the
     * tag set the live path builds in `ClaudeSession`'s ToolResult handler — so a **reloaded** transcript renders
     * a command's output as the same copyable code block a live one does, instead of the old plain-text block.
     *
     * Done as a pass over the finished list rather than inline, because the JSONL emits the `tool_result` in a
     * later message than its `tool_use`: at parse time the output's own line carries nothing that says "this came
     * from a command". Runs before [capTail] so a call trimmed out of the tail window can still have tagged its
     * output — the orphan-output rule then drops that output anyway, so the tag never outlives its call.
     */
    private fun tagCommandOutputs(entries: List<EntryDTO>): List<EntryDTO> {
        val commandCalls = entries.asSequence()
            .filter { it.speaker == "TOOL" && it.commandText != null }
            .mapNotNull { it.toolUseId }
            .toHashSet()
        if (commandCalls.isEmpty()) return entries
        return entries.map { e ->
            if (e.speaker != "TOOL_OUTPUT" || e.toolUseId !in commandCalls) return@map e
            // Space-separated tag set, same shape and order as the live path: "command", or "command error".
            e.copy(meta = if (e.meta == "error") "command error" else "command")
        }
    }

    /**
     * Keeps only the last [maxEntries] of [entries] (null/≤0 → unchanged). After taking the tail, drops every
     * orphan TOOL_OUTPUT row — any output whose TOOL call (by [EntryDTO.toolUseId]) is absent from the window,
     * whether the call was cut off by the tail boundary or simply never present — so the restored view never
     * shows a result without its call. Ordering of the remaining entries is preserved throughout.
     */
    private fun capTail(entries: List<EntryDTO>, maxEntries: Int?): List<EntryDTO> {
        if (maxEntries == null || maxEntries <= 0 || entries.size <= maxEntries) return entries
        val window = entries.subList(entries.size - maxEntries, entries.size)
        val seenToolIds = HashSet<String?>()
        // Identify tool ids that survive inside the window so we can tell orphan outputs from coherent ones.
        for (e in window) if (e.speaker == "TOOL") seenToolIds += e.toolUseId
        // Drop *every* orphan output anywhere in the window (not just leading ones), keeping the rest in order.
        return window.filterNot { e ->
            e.speaker == "TOOL_OUTPUT" && (e.toolUseId == null || e.toolUseId !in seenToolIds)
        }
    }

    private fun parseUser(obj: JsonObject, out: MutableList<EntryDTO>) {
        val content = (obj["message"] as? JsonObject)?.get("content") ?: return
        // The line's own flags, which the binary sets on the scaffolding it injects. `isCompactSummary`
        // marks the summary a compaction leaves behind: real content, but not something the user said.
        val isMeta = obj["isMeta"]?.jsonPrimitive?.booleanOrNull == true
        val isCompactSummary = obj["isCompactSummary"]?.jsonPrimitive?.booleanOrNull == true
        when (content) {
            is JsonPrimitive -> content.contentOrNull?.let { addUserText(it, isMeta, isCompactSummary, out) }

            is JsonArray -> content.mapNotNull { it as? JsonObject }
                .forEach { parseUserBlock(it, isMeta, isCompactSummary, out) }

            else -> Unit
        }
    }

    /**
     * Turns one `text` block of a `user` line into the row it really is.
     *
     * **Not every `text` block on a `user` line is the user.** The binary records its own scaffolding there —
     * the local-command caveat, the slash command the user ran, a settled subagent's notification — and
     * restoring them verbatim, styled as prompts, showed people paragraphs they had never written. See
     * [SyntheticUserText] for the closed tag set and why it is closed.
     */
    private fun addUserText(text: String, isMeta: Boolean, isCompactSummary: Boolean, out: MutableList<EntryDTO>) {
        if (isCompactSummary) {
            // The live path narrates a compaction as a system row; a restored one says the same thing.
            out += EntryDTO("SYSTEM", "Conversation compacted.")
            return
        }
        when (val kind = SyntheticUserText.classify(text, isMeta)) {
            is SyntheticUserText.Kind.Prompt -> out += EntryDTO("USER", kind.text)
            is SyntheticUserText.Kind.Command -> out += EntryDTO("USER", kind.text)
            is SyntheticUserText.Kind.SystemNote -> out += EntryDTO("SYSTEM", kind.text)
            SyntheticUserText.Kind.Hidden -> Unit
        }
    }

    /** One content block of a `user` line: the user's own text, or a tool_result the binary attributed to them. */
    private fun parseUserBlock(
        block: JsonObject,
        isMeta: Boolean,
        isCompactSummary: Boolean,
        out: MutableList<EntryDTO>,
    ) {
        when (block["type"]?.jsonPrimitive?.contentOrNull) {
            "text" -> block.text()?.let { addUserText(it, isMeta, isCompactSummary, out) }

            "tool_result" -> {
                val text = toolResultText(block["content"])
                if (text.isBlank()) return
                val id = block["tool_use_id"]?.jsonPrimitive?.contentOrNull
                // `error` here, `command` added later by tagCommandOutputs (the originating tool_use may
                // not have been parsed yet) — together they form the same space-separated tag set the
                // live path builds in ClaudeSession's ToolResult handler.
                val isError = block["is_error"]?.jsonPrimitive?.booleanOrNull == true
                out += EntryDTO("TOOL_OUTPUT", text, meta = if (isError) "error" else null, toolUseId = id)
            }
        }
    }

    private fun parseAssistant(obj: JsonObject, out: MutableList<EntryDTO>, projectRoot: String?) {
        val content = (obj["message"] as? JsonObject)?.get("content") as? JsonArray ?: return
        for (el in content) {
            val block = el as? JsonObject ?: continue
            when (block["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> block.text()?.let { out += EntryDTO("ASSISTANT", it) }

                "thinking" ->
                    block["thinking"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let { out += EntryDTO("THINKING", it) }

                "tool_use" -> {
                    val name = block["name"]?.jsonPrimitive?.contentOrNull ?: continue
                    val input = block["input"] as? JsonObject ?: JsonObject(emptyMap())
                    val id = block["id"]?.jsonPrimitive?.contentOrNull
                    // Same label + jump-to-code path as a LIVE tool call: a restored conversation must not show
                    // absolute paths (and dead cards) where a live one shows a project-relative link.
                    out += EntryDTO(
                        "TOOL",
                        ToolNaming.formatToolUse(name, input, projectRoot),
                        meta = name,
                        toolUseId = id,
                        filePath = ToolNaming.toolFilePath(name, input, projectRoot),
                        // Same command extraction as a LIVE call, so a reloaded transcript renders the current
                        // copyable code block instead of falling back to the old plain-text card.
                        commandText = ToolInputScanner.commandText(input),
                        messageText = ToolInputScanner.messageText(input),
                    )
                }
            }
        }
    }

    /**
     * How many past sessions "Open Previous Session…" offers. Each one costs a full JSONL read to recover its
     * title, so the cap is what keeps opening the list from scanning an archive that grows without bound.
     */
    private const val MAX_LISTED_SESSIONS = 30

    /** Past sessions for [project], newest-first, capped at [MAX_LISTED_SESSIONS]. */
    fun listSessions(project: Project): List<SessionRef> {
        val base = project.basePath ?: return emptyList()
        return SessionStore.listFiles(base).take(MAX_LISTED_SESSIONS).mapNotNull { path ->
            val id = path.fileName.toString().removeSuffix(".jsonl")
            val lines = runCatching { Files.readAllLines(path) }.getOrNull() ?: return@mapNotNull null
            val title = SessionTitleReader.pickTitle(lines) ?: id
            val mtime = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)
            val meta = parseMetadata(lines)
            SessionRef(id, title, mtime, meta.firstPrompt, meta.gitBranch, meta.createdAt)
        }
    }

    /** Best-effort session metadata extracted from a single pass over the JSONL header. */
    data class Metadata(val firstPrompt: String?, val gitBranch: String?, val createdAt: String?)

    /**
     * Scans raw JSONL [lines] for the first user prompt, the git branch and the earliest timestamp. Pure and
     * tolerant: corrupt/blank lines are skipped, and the first real user *text* (not a tool_result or a synthetic
     * caveat block) wins. The branch and creation time are taken from the first line that carries them. Never throws.
     */
    fun parseMetadata(lines: List<String>): Metadata {
        val acc = MetadataAccumulator()
        for (line in lines) {
            val obj = runCatching { JSON.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            acc.absorb(obj)
            // Every field found: the rest of the file cannot change the answer, so stop reading it.
            if (acc.isComplete) break
        }
        return acc.build()
    }

    /**
     * First-wins accumulator for the three session-list fields. Each is taken from the earliest line that
     * carries it, so the loop above only has to feed lines in and ask whether it can stop.
     */
    private class MetadataAccumulator {
        private var firstPrompt: String? = null
        private var branch: String? = null
        private var createdAt: String? = null

        val isComplete: Boolean get() = firstPrompt != null && branch != null && createdAt != null

        fun absorb(obj: JsonObject) {
            if (branch == null) branch = obj.nonBlank("gitBranch")
            if (createdAt == null) createdAt = obj.nonBlank("timestamp")
            if (firstPrompt == null && obj["type"]?.jsonPrimitive?.contentOrNull == "user") {
                firstPrompt = firstUserText(obj)
            }
        }

        fun build() = Metadata(firstPrompt, branch, createdAt)

        private fun JsonObject.nonBlank(key: String): String? =
            this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    /** The first plain-text prompt of a `user` line (string content, or the first `text` block of an array). */
    private fun firstUserText(obj: JsonObject): String? {
        val content = (obj["message"] as? JsonObject)?.get("content") ?: return null
        return when (content) {
            is JsonPrimitive -> content.contentOrNull?.takeIf { it.isNotBlank() }

            is JsonArray -> content.asSequence()
                .mapNotNull { it as? JsonObject }
                .firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                ?.text()?.takeIf { it.isNotBlank() }

            else -> null
        }
    }

    /** The `text` field of a content block, if present and a string. */
    private fun JsonObject.text(): String? = this["text"]?.jsonPrimitive?.contentOrNull

    /** A tool_result `content` is either a string or an array of `{type:"text",text:"…"}` blocks; concatenated. */
    private fun toolResultText(content: kotlinx.serialization.json.JsonElement?): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.mapNotNull { (it as? JsonObject)?.text() }.joinToString("\n")
        else -> ""
    }
}
