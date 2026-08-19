package dev.lain.claudejb.ui

import dev.lain.claudejb.session.BackgroundTaskRegistry
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.EntryDTO

internal object BackgroundTaskView {

    fun entries(session: ClaudeSession, taskId: String): List<EntryDTO> {
        val task = session.backgroundTaskRegistry.taskOf(taskId)
            ?: return listOf(EntryDTO("SYSTEM", "This background task is no longer known to this session."))
        val command = task.toolUseId?.let { session.transcript.commandTextOf(it) }
        return buildList {
            add(EntryDTO("SYSTEM", header(session, task)))
            command?.takeIf { it.isNotBlank() }?.let {
                add(EntryDTO("TOOL", "", meta = "Command", toolUseId = task.toolUseId, commandText = it))
            }
            val output = task.output.trim()
            if (output.isNotEmpty()) {
                add(EntryDTO("TOOL_OUTPUT", output, meta = "command", toolUseId = task.toolUseId))
            } else {
                add(EntryDTO("SYSTEM", noOutputNote(task)))
            }
        }
    }

    private fun header(session: ClaudeSession, task: BackgroundTaskRegistry.Task): String = buildString {
        append("**").append(task.label()).append("**\n\n")
        append("· State: ").append(if (task.running) "running" else "finished").append('\n')
        if (task.taskType.isNotBlank()) append("· Type: ").append(task.taskType).append('\n')
        append("· Started by: ").append(ownerLabel(session, task)).append('\n')
        task.outputFile?.takeIf { it.isNotBlank() }?.let { append("· Output file: ").append(it).append('\n') }
    }

    private fun ownerLabel(session: ClaudeSession, task: BackgroundTaskRegistry.Task): String {
        val agentId = session.ownerAgentOfTask(task.taskId) ?: return session.title
        return session.runningAgents.nodes[agentId]?.meta?.label() ?: session.title
    }

    private fun noOutputNote(task: BackgroundTaskRegistry.Task): String = when {
        !task.running -> "This task finished without reporting any output."

        task.outputFile != null -> "Waiting for the first output to be written."

        else ->
            "No output has been reported yet. A backgrounded command publishes its output only when it " +
                "is queried, so this fills in as the agent checks on it."
    }
}
