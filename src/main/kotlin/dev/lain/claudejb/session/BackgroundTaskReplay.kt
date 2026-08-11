package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.ProtocolParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Rebuilds a session's background tasks — and their output — from the binary's own transcript file.
 *
 * **Why this exists.** A background task only lives in memory: `background_tasks_changed` is a level signal
 * that stops mentioning a task the moment it ends, and nothing in it is written down by the plugin. So a
 * restarted IDE came back with the agents (their files are on disk) and with no tasks at all — the tabs, the
 * commands and every line they had produced were simply gone.
 *
 * **Why not persist them ourselves.** Everything needed is already written, once, by the binary: the session
 * JSONL carries the `tool_use` that launched the task (its command) and the `toolUseResult` that names it
 * (`backgroundTaskId`) together with whatever `stdout`/`stderr` came back. Copying that into a file of ours
 * would be a second store to keep in sync, go stale and leak — the same reasoning that keeps the agent index
 * down to ids.
 *
 * A replayed task is reported as **finished**: the transcript records what happened, not what is happening,
 * and the level signal re-announces anything still alive within a second of the process starting.
 *
 * Pure: takes lines, returns tasks. Everything it reads is per-line and tolerant — a malformed line is
 * skipped, never fatal.
 */
object BackgroundTaskReplay {

    /** One task recovered from the transcript, in the shape [BackgroundTaskRegistry] seeds from. */
    data class Replayed(
        val taskId: String,
        val toolUseId: String,
        val ownerToolUseId: String?,
        val command: String?,
        val outputFile: String?,
        val output: String,
        /**
         * What the binary answered when the task was launched or queried — the `tool_result` text.
         *
         * Kept because for most backgrounded commands it is the ONLY thing there is: `stdout` in the
         * structured result is empty at launch (verified across a real session's transcript), so a view built
         * from `stdout` alone showed an empty box for a task the binary had in fact reported on.
         */
        val notes: String = "",
    )

    private val JSON = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Every background task named anywhere in [lines], in the order they first appear.
     *
     * The join is on `backgroundTaskId`, and both halves come from the same file: the launching `tool_use`
     * block (for the command text) and the `toolUseResult` object on the `user` line that answers it.
     */
    fun parse(lines: List<String>): List<Replayed> {
        val commands = HashMap<String, String>() // tool_use_id → command text
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

    /** Remembers the command of every `tool_use` block, so a task can be labelled by what it actually runs. */
    private fun collectCommands(line: JsonObject, into: MutableMap<String, String>) {
        val content = (line["message"] as? JsonObject)?.get("content") as? JsonArray ?: return
        content.filterIsInstance<JsonObject>().forEach { block ->
            if (block.str("type") != "tool_use") return@forEach
            val id = block.str("id") ?: return@forEach
            val input = block["input"] as? JsonObject ?: return@forEach
            // `command` is Bash's; `script` covers the PowerShell/MCP shapes the guard already knows about.
            val command = input.str("command") ?: input.str("script") ?: return@forEach
            into[id] = command
        }
    }

    private fun collectTask(
        line: JsonObject,
        commands: Map<String, String>,
        into: MutableMap<String, Replayed>,
    ) {
        // The stream spells it `tool_use_result`; the transcript file spells it `toolUseResult`. Same object.
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
            // The FIRST sighting owns the attribution, exactly as in the live path: a later query of the same
            // task can come from another turn or another agent.
            ownerToolUseId = previous?.ownerToolUseId ?: line.str("parent_tool_use_id"),
            command = previous?.command ?: commands[toolUseId],
            // The path is in the transcript twice: the launching result says it in prose, and the
            // `<task-notification>` block repeats it as `<output-file>`. A replay has no events to listen to,
            // so this text IS the structured source here.
            outputFile = previous?.outputFile ?: output.outputFile ?: TaskOutputFile.parse(note),
            output = (previous?.output.orEmpty() + if (chunk.isBlank()) "" else "$chunk\n"),
            notes = listOf(previous?.notes.orEmpty(), note).filter { it.isNotBlank() }.joinToString("\n"),
        )
    }

    /** The `tool_result` block on a `user` line — the half of the join that names the call. */
    private fun resultBlock(line: JsonObject): JsonObject? =
        ((line["message"] as? JsonObject)?.get("content") as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.firstOrNull { it.str("type") == "tool_result" }

    /** The block's text, whichever of the two content shapes it arrived in. */
    private fun noteText(block: JsonObject): String = when (val body = block["content"]) {
        is kotlinx.serialization.json.JsonPrimitive -> body.contentOrNull.orEmpty()
        is JsonArray -> body.filterIsInstance<JsonObject>().mapNotNull { it.str("text") }.joinToString("\n")
        else -> ""
    }.trim()

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
}
