package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.ProtocolParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object BackgroundTaskReplay {

    data class Replayed(
        val taskId: String,
        val toolUseId: String,
        val ownerToolUseId: String?,
        val command: String?,
        val outputFile: String?,
        val output: String,
        val notes: String = "",
    )

    private val JSON = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(lines: List<String>): List<Replayed> {
        val commands = HashMap<String, String>()
        val tasks = LinkedHashMap<String, Replayed>()

        for (line in lines) {
            if (line.isBlank()) continue
            val obj = runCatching { JSON.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            when (obj.str("type")) {
                "assistant" -> collectCommands(obj, commands)
                "user" -> collectTask(obj, commands, tasks)
                else -> Unit
            }
        }
        return tasks.values.toList()
    }

    private fun collectCommands(line: JsonObject, into: MutableMap<String, String>) {
        val content = (line["message"] as? JsonObject)?.get("content") as? JsonArray ?: return
        content.filterIsInstance<JsonObject>().forEach { block ->
            if (block.str("type") != "tool_use") return@forEach
            val id = block.str("id") ?: return@forEach
            val input = block["input"] as? JsonObject ?: return@forEach
            val command = input.str("command") ?: input.str("script") ?: return@forEach
            into[id] = command
        }
    }

    private fun collectTask(
        line: JsonObject,
        commands: Map<String, String>,
        into: MutableMap<String, Replayed>,
    ) {
        val resultObj = (line["toolUseResult"] ?: line["tool_use_result"]) as? JsonObject ?: return
        val output = ProtocolParser.parseToolOutput(resultObj) ?: return
        val taskId = output.backgroundTaskId ?: return
        val block = resultBlock(line) ?: return
        val toolUseId = block.str("tool_use_id") ?: return
        val chunk = listOfNotNull(output.stdout, output.stderr).filter { it.isNotBlank() }.joinToString("\n")
        val note = noteText(block)
        val previous = into[taskId]
        into[taskId] = Replayed(
            taskId = taskId,
            toolUseId = previous?.toolUseId ?: toolUseId,
            ownerToolUseId = previous?.ownerToolUseId ?: line.str("parent_tool_use_id"),
            command = previous?.command ?: commands[toolUseId],
            outputFile = previous?.outputFile ?: output.outputFile ?: TaskOutputFile.parse(note),
            output = (previous?.output.orEmpty() + if (chunk.isBlank()) "" else "$chunk\n"),
            notes = listOf(previous?.notes.orEmpty(), note).filter { it.isNotBlank() }.joinToString("\n"),
        )
    }

    private fun resultBlock(line: JsonObject): JsonObject? =
        ((line["message"] as? JsonObject)?.get("content") as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.firstOrNull { it.str("type") == "tool_result" }

    private fun noteText(block: JsonObject): String = when (val body = block["content"]) {
        is kotlinx.serialization.json.JsonPrimitive -> body.contentOrNull.orEmpty()
        is JsonArray -> body.filterIsInstance<JsonObject>().mapNotNull { it.str("text") }.joinToString("\n")
        else -> ""
    }.trim()

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
}
