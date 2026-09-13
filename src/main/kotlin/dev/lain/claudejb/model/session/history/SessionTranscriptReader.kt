package dev.lain.claudejb.model.session.history

import dev.lain.claudejb.model.mcp.OwnTools
import dev.lain.claudejb.model.permission.scan.ToolInputScanner
import dev.lain.claudejb.model.session.transcript.EntryDTO
import dev.lain.claudejb.model.session.transcript.SyntheticUserText
import dev.lain.claudejb.model.session.transcript.ToolNaming
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

object SessionTranscriptReader {

    private val JSON = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    const val DEFAULT_RESTORE_CAP: Int = 200

    private const val TOON = "toon"

    fun readEntries(sessionId: String, maxEntries: Int? = null, projectRoot: String? = null): List<EntryDTO> =
        SessionStore.readLines(sessionId)?.let { parseEntries(it, maxEntries, projectRoot) } ?: emptyList()

    fun parseEntries(lines: List<String>, maxEntries: Int? = null, projectRoot: String? = null): List<EntryDTO> =
        entriesOf(parseRecords(lines), maxEntries, projectRoot)

    fun parseRecord(line: String): JsonObject? =
        if (line.isBlank()) null else runCatching { JSON.parseToJsonElement(line).jsonObject }.getOrNull()

    fun parseRecords(lines: List<String>): List<JsonObject> = lines.mapNotNull(::parseRecord)

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
        return capTail(markInFlight(decodeOwnOutputs(tagCommandOutputs(out))), maxEntries)
    }

    private fun decodeOwnOutputs(entries: List<EntryDTO>): List<EntryDTO> {
        val ownCalls = entries.asSequence()
            .filter { it.speaker == "TOOL" && OwnTools.isOwn(it.meta) }
            .mapNotNull { it.toolUseId }
            .toHashSet()
        if (ownCalls.isEmpty()) return entries
        return entries.map { e ->
            if (e.speaker != "TOOL_OUTPUT" || e.meta != null || e.toolUseId !in ownCalls) return@map e
            OwnTools.decodeResult(e.text)?.let { e.copy(text = it.toString(), meta = TOON) } ?: e
        }
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

                "tool_use" -> toolUse(block, origin, projectRoot)?.let { out += it }
            }
        }
    }

    private fun toolUse(block: JsonObject, origin: Origin, projectRoot: String?): EntryDTO? {
        val name = block["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val input = block["input"] as? JsonObject ?: JsonObject(emptyMap())
        val own = OwnTools.parse(name, input)
        val args = OwnTools.argsOf(input)
        return EntryDTO(
            "TOOL",
            own?.let { OwnTools.label(it, args) } ?: ToolNaming.formatToolUse(name, input, projectRoot),
            meta = name,
            toolUseId = block["id"]?.jsonPrimitive?.contentOrNull,
            parentToolUseId = origin.parent,
            atMillis = origin.atMillis,
            filePath = if (own != null) OwnTools.path(args) else ToolNaming.toolFilePath(name, input, projectRoot),
            commandText = ToolInputScanner.commandText(input),
            messageText = if (own != null) OwnTools.argsToon(args) else ToolInputScanner.messageText(input),
        )
    }

    private fun JsonObject.text(): String? = this["text"]?.jsonPrimitive?.contentOrNull

    private fun toolResultText(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.mapNotNull { (it as? JsonObject)?.text() }.joinToString("\n")
        else -> ""
    }
}
