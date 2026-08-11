package dev.lain.claudejb.ui

import dev.lain.claudejb.session.BackgroundTaskRegistry
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.EntryDTO

/**
 * What a background task's tab shows, as transcript rows.
 *
 * A background task is a process, not a conversation: there is no transcript to read. What there IS, is what
 * the binary reported — the task's own description and type, the tool call that started it, and its output.
 * Rendering that through the same rows the agent tabs use keeps one renderer for everything the browser
 * paints, instead of a second layout that has to be kept looking like the first.
 *
 * **The honest gap is part of the design.** A backgrounded shell command publishes no progress file: its
 * output only exists once the binary is asked for it, and the plugin cannot ask. So a task with nothing
 * reported yet says exactly that — rather than an empty box that reads as "it produced nothing".
 *
 * Pure: takes a session, returns rows. Testable without a browser.
 */
internal object BackgroundTaskView {

    fun entries(session: ClaudeSession, taskId: String): List<EntryDTO> {
        val task = session.backgroundTaskRegistry.taskOf(taskId)
            ?: return listOf(EntryDTO("SYSTEM", "This background task is no longer known to this session."))
        val command = task.toolUseId?.let { session.transcript.commandTextOf(it) }
        return buildList {
            add(EntryDTO("SYSTEM", header(session, task)))
            // What was launched, as its own copyable code block — the same card a Bash call gets in the
            // chat. For a shell task this is the most useful thing there is to show, and it is known from
            // the moment the task exists.
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

    /** The owning agent's label, or the chat's own name when the task was started by the main turn. */
    private fun ownerLabel(session: ClaudeSession, task: BackgroundTaskRegistry.Task): String {
        val agentId = session.ownerAgentOfTask(task.taskId) ?: return session.title
        return session.runningAgents.nodes[agentId]?.meta?.label() ?: session.title
    }

    private fun noOutputNote(task: BackgroundTaskRegistry.Task): String = when {
        !task.running -> "This task finished without reporting any output."

        task.outputFile != null -> "Waiting for the first output to be written."

        // The protocol-level gap, stated rather than papered over: `tool_progress` is a heartbeat with no
        // payload, and a backgrounded command's output reaches the stream only when the binary is queried
        // for it — which the agent does, and the host cannot.
        else ->
            "No output has been reported yet. A backgrounded command publishes its output only when it " +
                "is queried, so this fills in as the agent checks on it."
    }
}
