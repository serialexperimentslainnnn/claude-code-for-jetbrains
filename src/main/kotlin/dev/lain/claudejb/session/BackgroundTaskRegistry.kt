package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.BackgroundTaskInfo
import dev.lain.claudejb.protocol.ClaudeEvent
import java.util.concurrent.ConcurrentHashMap

class BackgroundTaskRegistry(
    private val now: () -> Long = System::currentTimeMillis,
    private val runStartedAtMillis: Long = WorkloadWindow.RUN_STARTED_AT,
) {

    data class Task(
        val taskId: String,
        val description: String = "",
        val taskType: String = "",
        val running: Boolean = true,
        val toolUseId: String? = null,
        val ownerToolUseId: String? = null,
        val outputFile: String? = null,
        val output: String = "",
        val command: String? = null,
        val seenLive: Boolean = false,
        val completedAtMillis: Long? = null,
    ) {
        fun label(): String =
            description.ifBlank { command?.lineSequence()?.firstOrNull().orEmpty() }
                .ifBlank { taskType }
                .ifBlank { taskId }
    }

    private val tasks = ConcurrentHashMap<String, Task>()

    private val order = java.util.concurrent.CopyOnWriteArrayList<String>()

    val all: List<Task> get() = order.mapNotNull { tasks[it] }

    fun taskOf(taskId: String): Task? = tasks[taskId]

    val anyTailable: Boolean get() = tasks.values.any { it.running && !it.outputFile.isNullOrBlank() }

    fun seed(replayed: List<BackgroundTaskReplay.Replayed>): Boolean {
        var changed = false
        replayed.forEach { r ->
            if (tasks.containsKey(r.taskId)) return@forEach
            order += r.taskId
            tasks[r.taskId] = Task(
                taskId = r.taskId,
                running = false,
                toolUseId = r.toolUseId,
                ownerToolUseId = r.ownerToolUseId,
                outputFile = r.outputFile,
                output = r.output.ifBlank { r.notes }.takeLast(MAX_OUTPUT),
                command = r.command,
                completedAtMillis = runStartedAtMillis,
            )
            changed = true
        }
        return changed
    }

    fun observeLevel(live: List<BackgroundTaskInfo>): Boolean {
        var changed = false
        val liveIds = live.filterNot { it.taskType == AGENT_TASK_TYPE }.associateBy { it.taskId }
        liveIds.forEach { (id, info) ->
            val previous = tasks[id] ?: return@forEach
            val next = previous.copy(
                description = info.description.ifBlank { previous.description },
                taskType = info.taskType.ifBlank { previous.taskType },
                running = true,
                seenLive = true,
                completedAtMillis = null,
            )
            if (next != previous) {
                tasks[id] = next
                changed = true
            }
        }
        tasks.forEach { (id, task) ->
            if (task.running && task.seenLive && id !in liveIds) {
                tasks[id] = task.copy(running = false, completedAtMillis = task.completedAtMillis ?: now())
                changed = true
            }
        }
        return changed
    }

    fun settle(taskId: String, status: String?): Boolean {
        if (status !in TERMINAL_STATUSES) return false
        val previous = tasks[taskId] ?: return false
        if (!previous.running) return false
        tasks[taskId] = previous.copy(running = false, completedAtMillis = previous.completedAtMillis ?: now())
        return true
    }

    fun observeOutputFile(taskId: String, outputFile: String?): Boolean {
        val path = outputFile?.takeIf { it.isNotBlank() } ?: return false
        val previous = tasks[taskId] ?: return false
        if (previous.outputFile == path) return false
        tasks[taskId] = previous.copy(outputFile = path)
        return true
    }

    fun observe(event: ClaudeEvent.ToolResult): Boolean {
        val out = event.output ?: return false
        val taskId = out.backgroundTaskId ?: return false
        val chunk = listOfNotNull(out.stdout, out.stderr).filter { it.isNotBlank() }.joinToString("\n")
        val previous = tasks[taskId]
        val next = (previous ?: Task(taskId)).copy(
            toolUseId = event.toolUseId,
            ownerToolUseId = previous?.ownerToolUseId ?: event.parentToolUseId,
            outputFile = previous?.outputFile ?: out.outputFile ?: TaskOutputFile.parse(event.content),
            output = (previous?.output.orEmpty() + if (chunk.isBlank()) "" else "$chunk\n").takeLast(MAX_OUTPUT),
        )
        if (next == previous) return false
        if (previous == null) order += taskId
        tasks[taskId] = next
        return true
    }

    fun appendTailedOutput(taskId: String, text: String): Boolean {
        if (text.isBlank()) return false
        val previous = tasks[taskId] ?: return false
        val merged = (previous.output + text).takeLast(MAX_OUTPUT)
        if (merged == previous.output) return false
        tasks[taskId] = previous.copy(output = merged)
        return true
    }

    fun clear() {
        tasks.clear()
        order.clear()
    }

    companion object {
        const val AGENT_TASK_TYPE = "local_agent"

        private val TERMINAL_STATUSES = setOf("completed", "failed", "cancelled", "stopped", "killed", "error")

        private const val MAX_OUTPUT = 200_000
    }
}
