package dev.lain.claudejb.session

import com.intellij.openapi.project.Project
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
     *  [ClaudeSession.toolFilePath]). Null on every other row, and on any row parsed without a project root. */
    val filePath: String? = null,
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

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

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
        return capTail(out, maxEntries)
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
        when (content) {
            is JsonPrimitive -> content.contentOrNull?.takeIf { it.isNotBlank() }?.let { out += EntryDTO("USER", it) }
            is JsonArray -> for (el in content) {
                val block = el as? JsonObject ?: continue
                when (block["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> block.text()?.takeIf { it.isNotBlank() }?.let { out += EntryDTO("USER", it) }
                    "tool_result" -> {
                        val text = toolResultText(block["content"])
                        val id = block["tool_use_id"]?.jsonPrimitive?.contentOrNull
                        if (text.isNotBlank()) out += EntryDTO("TOOL_OUTPUT", text, toolUseId = id)
                    }
                }
            }
            else -> Unit
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
                        ClaudeSession.formatToolUse(name, input, projectRoot),
                        meta = name,
                        toolUseId = id,
                        filePath = ClaudeSession.toolFilePath(name, input, projectRoot),
                    )
                }
            }
        }
    }

    /** Past sessions for [project], newest-first, capped at 30 (avoids reading the whole archive). */
    fun listSessions(project: Project): List<SessionRef> {
        val base = project.basePath ?: return emptyList()
        return SessionStore.listFiles(base).take(30).mapNotNull { path ->
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
        var firstPrompt: String? = null
        var branch: String? = null
        var createdAt: String? = null
        for (line in lines) {
            if (line.isBlank()) continue
            val obj = runCatching { JSON.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            if (branch == null) obj["gitBranch"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { branch = it }
            if (createdAt == null) obj["timestamp"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { createdAt = it }
            if (firstPrompt == null && obj["type"]?.jsonPrimitive?.contentOrNull == "user") {
                firstPrompt = firstUserText(obj)
            }
            if (firstPrompt != null && branch != null && createdAt != null) break
        }
        return Metadata(firstPrompt, branch, createdAt)
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
