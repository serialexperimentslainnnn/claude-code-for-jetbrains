package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.BackgroundTaskInfo
import dev.lain.claudejb.protocol.ClaudeEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Every background task this session has seen: what it is, who started it, and whatever output came back.
 *
 * **Why the plugin keeps its own record instead of rendering the binary's set.**
 * `system/background_tasks_changed` is a LEVEL signal with REPLACE semantics — it lists what is live *right
 * now*. Rendering it directly is correct for "is anything running", and wrong for a tab: the moment a task
 * finished it vanished from the payload, so its row and its tab disappeared with it and there was no way to
 * read what it had done. A finished agent keeps its tab; so does a finished task. The level is still the only
 * source of truth for *liveness* — presence means running, absence means finished — which is exactly how it
 * is used here, without ever pairing it with the edge stream the SDK says not to pair it with.
 *
 * **Where the rest comes from.** The level carries `{task_id, task_type, description}` and nothing else: no
 * parent, no tool call, no output. That link lives in the STRUCTURED tool output (`tool_use_result`), where a
 * backgrounded call reports `backgroundTaskId` (verified in a real session's transcript, `claude` 2.1.226;
 * typed in the SDK as `BashOutput.backgroundTaskId` and `AgentOutput.outputFile`). Joining on it gives:
 *  - the **owner** — the starting call's `parent_tool_use_id`, i.e. the agent whose turn ran it, or the chat
 *    when there is none. Stored raw and resolved by the caller, since an agent may be admitted later;
 *  - the **card** — [Task.toolUseId], the transcript row that started it;
 *  - the **output** — a backgrounded agent publishes a progress file ([Task.outputFile]) that can be tailed;
 *    a backgrounded shell command publishes no file, so what is shown is what the binary actually reported:
 *    the initial result plus each later query of it. A task with nothing reported says so rather than
 *    showing a plausible blank.
 *
 * Agents are excluded ([AGENT_TASK_TYPE]): to the binary a running agent IS a background task, and it already
 * has its own rows, its own tabs and its own transcripts. Keeping it here too is how a "Background tasks" row
 * ended up holding an agent — of itself.
 *
 * Threading: written from the EDT, read from the UI and from the pooled scan; concurrent for the same reason
 * [TaskTracker]'s map is.
 */
class BackgroundTaskRegistry {

    /** One background task. [running] comes from the level signal: present means live, absent means done. */
    data class Task(
        val taskId: String,
        val description: String = "",
        val taskType: String = "",
        val running: Boolean = true,
        val toolUseId: String? = null,
        val ownerToolUseId: String? = null,
        val outputFile: String? = null,
        val output: String = "",
        /** The command that launched it, when the transcript carried one — the most useful label there is. */
        val command: String? = null,
        /**
         * Whether the level signal has ever listed this task as live.
         *
         * Absence from the level only means "finished" for a task the level once CLAIMED. Without this, the
         * first `background_tasks_changed` after a task started — a signal that may not carry shell tasks at
         * all — settled it on the spot: the row went green while the command was still writing output, which
         * is exactly what the user sees as "finished" on something plainly still running.
         */
        val seenLive: Boolean = false,
    ) {
        fun label(): String =
            description.ifBlank { command?.lineSequence()?.firstOrNull().orEmpty() }
                .ifBlank { taskType }
                .ifBlank { taskId }
    }

    private val tasks = ConcurrentHashMap<String, Task>()

    /** Insertion order, so a row does not reshuffle under the pointer every time the level signal fires. */
    private val order = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** Every task ever seen this process, live ones and finished ones, in the order they appeared. */
    val all: List<Task> get() = order.mapNotNull { tasks[it] }

    fun taskOf(taskId: String): Task? = tasks[taskId]

    /** Whether anything is still running with a file worth tailing — what keeps the live-output poll alive. */
    val anyTailable: Boolean get() = tasks.values.any { it.running && !it.outputFile.isNullOrBlank() }

    /**
     * Applies the level signal: everything in [live] is running, everything previously seen and absent from
     * it is finished. Returns true when anything changed.
     */
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
                // The chunks when there are any; otherwise what the binary SAID about the task — for most
                // backgrounded commands the launching result's prose is the only output there is.
                output = r.output.ifBlank { r.notes }.takeLast(MAX_OUTPUT),
                command = r.command,
            )
            changed = true
        }
        return changed
    }

    fun observeLevel(live: List<BackgroundTaskInfo>): Boolean {
        var changed = false
        val liveIds = live.filterNot { it.taskType == AGENT_TASK_TYPE }.associateBy { it.taskId }
        liveIds.forEach { (id, info) ->
            // UPDATE ONLY — never create. This signal reports the binary's whole live set, and its ids do
            // not always match the `backgroundTaskId` a tool_result gave us: adopting the strangers produced
            // a second, contentless copy of every task ("Background Task (background)", no command, no
            // output) sitting next to the real one. The tool_result is what makes a task OURS and gives it
            // its command; this only says whether it is still running.
            val previous = tasks[id] ?: return@forEach
            val next = previous.copy(
                description = info.description.ifBlank { previous.description },
                taskType = info.taskType.ifBlank { previous.taskType },
                running = true,
                seenLive = true,
            )
            if (next != previous) {
                tasks[id] = next
                changed = true
            }
        }
        tasks.forEach { (id, task) ->
            // …and only settles what it once CLAIMED. Absence is not evidence of an ending for a task this
            // signal never listed: a backgrounded shell command is created from its `tool_result`, and the
            // next level signal — which may only ever carry agents — turned it green while it was still
            // writing output. What ends such a task is its own `task_notification` ([settle]).
            if (task.running && task.seenLive && id !in liveIds) {
                tasks[id] = task.copy(running = false)
                changed = true
            }
        }
        return changed
    }

    /**
     * Ends a task from its own `system/task_notification`, which names it and carries a terminal status.
     *
     * The authoritative ending for a task the level signal never listed — see [observeLevel]. An unknown or
     * non-terminal status leaves it alone: only an ending ends it, the same rule the agent tabs follow.
     */
    fun settle(taskId: String, status: String?): Boolean {
        if (status !in TERMINAL_STATUSES) return false
        val previous = tasks[taskId] ?: return false
        if (!previous.running) return false
        tasks[taskId] = previous.copy(running = false)
        return true
    }

    /**
     * Records where a task's output is being written, from `system/task_notification`'s `output_file`.
     *
     * The structured source, and the one that matters: with it the task's output is a file the plugin can
     * tail, live and after a restart, instead of the "no output was reported" the view used to show for a
     * command that had plainly produced some.
     *
     * UPDATE ONLY — never create, for the same reason [observeLevel] never creates. `task_notification` is
     * emitted for AGENTS too, and creating from it registered every agent as a background task: a row per
     * agent with no description ("Background Task (background)"), whose "output" was the agent's own
     * transcript file — pages of raw JSONL where a command's output should be. The id gave it away: the
     * task id was the agent id. A `tool_result` carrying `backgroundTaskId` is what makes a task ours.
     */
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
            // The FIRST sighting owns the attribution: a later query of the task's output can come from a
            // different turn or a different agent, and letting that overwrite it would re-parent the task to
            // whoever last looked at it.
            ownerToolUseId = previous?.ownerToolUseId ?: event.parentToolUseId,
            // The launching result NAMES the file the output is going to ("Output is being written to: …"),
            // which is what makes a running task's output readable before it settles — until then there is no
            // `task_notification` to carry the structured field.
            outputFile = previous?.outputFile ?: out.outputFile ?: TaskOutputFile.parse(event.content),
            output = (previous?.output.orEmpty() + if (chunk.isBlank()) "" else "$chunk\n").takeLast(MAX_OUTPUT),
        )
        if (next == previous) return false
        if (previous == null) order += taskId
        tasks[taskId] = next
        return true
    }

    /** Appends text read from a task's progress file (see [Task.outputFile]). True when it changed anything. */
    fun appendTailedOutput(taskId: String, text: String): Boolean {
        if (text.isBlank()) return false
        val previous = tasks[taskId] ?: return false
        val merged = (previous.output + text).takeLast(MAX_OUTPUT)
        if (merged == previous.output) return false
        tasks[taskId] = previous.copy(output = merged)
        return true
    }

    /** Per-process state, like the task set itself: a restarted binary re-announces whatever is still alive. */
    fun clear() {
        tasks.clear()
        order.clear()
    }

    companion object {
        /** The `task_type` the binary uses for a running agent — it has its own rows; see the class doc. */
        const val AGENT_TASK_TYPE = "local_agent"

        /** `task_notification` statuses that END a task. Anything else is progress, and progress is not an end. */
        private val TERMINAL_STATUSES = setOf("completed", "failed", "cancelled", "stopped", "killed", "error")

        /**
         * Cap on retained output per task. A backgrounded `tail -f` is unbounded by nature, and this is a
         * view of a running thing, not an archive — the file on disk is the archive.
         */
        private const val MAX_OUTPUT = 200_000
    }
}
