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
import java.time.Instant

data class EntryDTO(
    val speaker: String,
    val text: String,
    val meta: String? = null,
    val toolUseId: String? = null,
    val parentToolUseId: String? = null,
    val atMillis: Long? = null,
    val filePath: String? = null,
    val commandText: String? = null,
    val messageText: String? = null,
    val inFlight: Boolean = false,
    val failed: Boolean = false,
    val blockedRule: String? = null,
    val bypassedRule: String? = null,
    val bypassAction: String? = null,
)

data class SessionRef(
    val sessionId: String,
    val title: String,
    val lastModified: Long,
    val firstPrompt: String? = null,
    val gitBranch: String? = null,
    val createdAt: String? = null,
)

object SessionTranscriptReader {

    private val JSON = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    const val DEFAULT_RESTORE_CAP: Int = 200

    fun readEntries(sessionId: String, maxEntries: Int? = null, projectRoot: String? = null): List<EntryDTO> =
        SessionStore.readLines(sessionId)?.let { parseEntries(it, maxEntries, projectRoot) } ?: emptyList()

    fun parseEntries(lines: List<String>, maxEntries: Int? = null, projectRoot: String? = null): List<EntryDTO> =
        entriesOf(parseRecords(lines), maxEntries, projectRoot)

    fun parseRecords(lines: List<String>): List<JsonObject> =
        lines.mapNotNull { line ->
            if (line.isBlank()) null else runCatching { JSON.parseToJsonElement(line).jsonObject }.getOrNull()
        }

    fun entriesOf(
        records: List<JsonObject>,
        maxEntries: Int? = null,
        projectRoot: String? = null,
    ): List<EntryDTO> {
        val out = ArrayList<EntryDTO>()
        for (obj in records) {
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

    private fun markInFlight(entries: List<EntryDTO>): List<EntryDTO> {
        val answered = HashSet<String>()
        val failed = HashSet<String>()
        for (e in entries) {
            if (e.speaker != "TOOL_OUTPUT") continue
            val id = e.toolUseId ?: continue
            answered += id
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

    private fun tagCommandOutputs(entries: List<EntryDTO>): List<EntryDTO> {
        val commandCalls = entries.asSequence()
            .filter { it.speaker == "TOOL" && it.commandText != null }
            .mapNotNull { it.toolUseId }
            .toHashSet()
        if (commandCalls.isEmpty()) return entries
        return entries.map { e ->
            if (e.speaker != "TOOL_OUTPUT" || e.toolUseId !in commandCalls) return@map e
            e.copy(meta = if (e.meta == "error") "command error" else "command")
        }
    }

    private fun capTail(entries: List<EntryDTO>, maxEntries: Int?): List<EntryDTO> {
        if (maxEntries == null || maxEntries <= 0 || entries.size <= maxEntries) return entries
        val window = entries.subList(entries.size - maxEntries, entries.size)
        val seenToolIds = HashSet<String?>()
        for (e in window) if (e.speaker == "TOOL") seenToolIds += e.toolUseId
        return window.filterNot { e ->
            e.speaker == "TOOL_OUTPUT" && (e.toolUseId == null || e.toolUseId !in seenToolIds)
        }
    }

    private fun parseUser(obj: JsonObject, out: MutableList<EntryDTO>) {
        val content = (obj["message"] as? JsonObject)?.get("content") ?: return
        val isMeta = obj["isMeta"]?.jsonPrimitive?.booleanOrNull == true
        val isCompactSummary = obj["isCompactSummary"]?.jsonPrimitive?.booleanOrNull == true
        val origin = originOf(obj)
        when (content) {
            is JsonPrimitive -> content.contentOrNull?.let { addUserText(it, isMeta, isCompactSummary, origin, out) }

            is JsonArray -> content.mapNotNull { it as? JsonObject }
                .forEach { parseUserBlock(it, isMeta, isCompactSummary, origin, out) }

            else -> Unit
        }
    }

    private data class Origin(val parent: String?, val atMillis: Long?)

    private fun originOf(obj: JsonObject) = Origin(parentToolUseOf(obj), stampOf(obj))

    private fun parentToolUseOf(obj: JsonObject): String? =
        obj["parent_tool_use_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun stampOf(obj: JsonObject): Long? =
        obj["timestamp"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun entry(speaker: String, text: String, origin: Origin) =
        EntryDTO(speaker, text, parentToolUseId = origin.parent, atMillis = origin.atMillis)

    private fun addUserText(
        text: String,
        isMeta: Boolean,
        isCompactSummary: Boolean,
        origin: Origin,
        out: MutableList<EntryDTO>,
    ) {
        if (isCompactSummary) {
            out += entry("SYSTEM", "Conversation compacted.", origin)
            return
        }
        when (val kind = SyntheticUserText.classify(text, isMeta)) {
            is SyntheticUserText.Kind.Prompt -> out += entry("USER", kind.text, origin)
            is SyntheticUserText.Kind.Command -> out += entry("USER", kind.text, origin)
            is SyntheticUserText.Kind.SystemNote -> out += entry("SYSTEM", kind.text, origin)
            SyntheticUserText.Kind.Hidden -> Unit
        }
    }

    private fun parseUserBlock(
        block: JsonObject,
        isMeta: Boolean,
        isCompactSummary: Boolean,
        origin: Origin,
        out: MutableList<EntryDTO>,
    ) {
        when (block["type"]?.jsonPrimitive?.contentOrNull) {
            "text" -> block.text()?.let { addUserText(it, isMeta, isCompactSummary, origin, out) }

            "tool_result" -> {
                val text = toolResultText(block["content"])
                if (text.isBlank()) return
                val id = block["tool_use_id"]?.jsonPrimitive?.contentOrNull
                val isError = block["is_error"]?.jsonPrimitive?.booleanOrNull == true
                out += EntryDTO(
                    "TOOL_OUTPUT",
                    text,
                    meta = if (isError) "error" else null,
                    toolUseId = id,
                    parentToolUseId = origin.parent,
                    atMillis = origin.atMillis,
                )
            }
        }
    }

    private fun parseAssistant(obj: JsonObject, out: MutableList<EntryDTO>, projectRoot: String?) {
        val content = (obj["message"] as? JsonObject)?.get("content") as? JsonArray ?: return
        val origin = originOf(obj)
        for (el in content) {
            val block = el as? JsonObject ?: continue
            when (block["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> block.text()?.let { out += entry("ASSISTANT", it, origin) }

                "thinking" ->
                    block["thinking"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let { out += entry("THINKING", it, origin) }

                "tool_use" -> {
                    val name = block["name"]?.jsonPrimitive?.contentOrNull ?: continue
                    val input = block["input"] as? JsonObject ?: JsonObject(emptyMap())
                    val id = block["id"]?.jsonPrimitive?.contentOrNull
                    out += EntryDTO(
                        "TOOL",
                        ToolNaming.formatToolUse(name, input, projectRoot),
                        meta = name,
                        toolUseId = id,
                        parentToolUseId = origin.parent,
                        atMillis = origin.atMillis,
                        filePath = ToolNaming.toolFilePath(name, input, projectRoot),
                        commandText = ToolInputScanner.commandText(input),
                        messageText = ToolInputScanner.messageText(input),
                    )
                }
            }
        }
    }

    private const val MAX_LISTED_SESSIONS = 30

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

    data class Metadata(val firstPrompt: String?, val gitBranch: String?, val createdAt: String?)

    fun parseMetadata(lines: List<String>): Metadata {
        val acc = MetadataAccumulator()
        for (line in lines) {
            val obj = runCatching { JSON.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            acc.absorb(obj)
            if (acc.isComplete) break
        }
        return acc.build()
    }

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

    private fun JsonObject.text(): String? = this["text"]?.jsonPrimitive?.contentOrNull

    private fun toolResultText(content: kotlinx.serialization.json.JsonElement?): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.mapNotNull { (it as? JsonObject)?.text() }.joinToString("\n")
        else -> ""
    }
}
