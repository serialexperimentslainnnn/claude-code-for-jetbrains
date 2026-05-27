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
)

/** Lightweight handle to a past session: its id, the binary-issued title, and the file mtime (newest-first sort key). */
data class SessionRef(
    val sessionId: String,
    val title: String,
    val lastModified: Long,
)

/**
 * Read-only reconstruction of a past conversation from the `claude` binary's transcript (the single source
 * of truth — see [SessionStore]). Parsing is pure and tolerant: every line is decoded under runCatching and
 * unknown line types (ai-title, mode, queue-operation, last-prompt, attachment, summary…) are skipped.
 * IO is blocking — call [readEntries]/[listSessions] off the EDT.
 */
object SessionTranscriptReader {

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Decoded transcript for [sessionId], or empty if the sidecar is absent/unreadable. */
    fun readEntries(sessionId: String): List<EntryDTO> =
        SessionStore.readLines(sessionId)?.let { parseEntries(it) } ?: emptyList()

    /**
     * Maps raw JSONL [lines] to the plugin's transcript model. Pure and unit-testable; never throws — a
     * corrupt/blank line is dropped. See [readEntries] for the IO-backed entry point.
     */
    fun parseEntries(lines: List<String>): List<EntryDTO> {
        val out = ArrayList<EntryDTO>()
        for (line in lines) {
            if (line.isBlank()) continue
            val obj = runCatching { JSON.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            runCatching {
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "user" -> parseUser(obj, out)
                    "assistant" -> parseAssistant(obj, out)
                    else -> Unit
                }
            }
        }
        return out
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

    private fun parseAssistant(obj: JsonObject, out: MutableList<EntryDTO>) {
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
                    out += EntryDTO("TOOL", ClaudeSession.formatToolUse(name, input), meta = name, toolUseId = id)
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
            SessionRef(id, title, mtime)
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
