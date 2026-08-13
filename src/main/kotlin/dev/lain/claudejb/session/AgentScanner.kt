package dev.lain.claudejb.session

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeping the agent tree and the background tasks in step with what is on disk.
 *
 * The binary writes both — a sidecar and a transcript per agent, a progress file per backgrounded agent —
 * and none of it arrives as an event. So this walks that directory when something suggests it changed,
 * off the EDT, and tells the UI what is new.
 *
 * Its own class because the walking, the tailing, the index bookkeeping and the coalescing of concurrent
 * requests are one job with one failure mode, and none of it is a turn: [ClaudeSession] neither reads nor
 * writes any of this state, it only asks for a scan and hears back.
 */
class AgentScanner(
    private val project: Project,
    private val agents: AgentRegistry,
    private val tasks: BackgroundTaskRegistry,
    /** The session id, once the binary has reported one — there is nothing to record before that. */
    private val sessionId: () -> String?,
    /** Which agent started a task, resolved by the session (the one rule, so the views cannot disagree). */
    private val ownerOfTask: (String) -> String?,
    private val ui: Ui,
) {

    /** What a finished scan has to tell the GUI. One interface rather than four lambdas: they always arrive
     *  together, in this order, and naming them is what makes the call site at the bottom of [scan] readable. */
    interface Ui {
        /** Names and states the Agent cards in the transcript. Runs on the EDT. */
        fun labelCards()

        /** Newly admitted agents: the caller raises a tab, blinks it and notifies. EDT. */
        fun onFresh(fresh: List<String>)

        /** A task's file grew: no agent news, but the tab showing that output has to repaint. EDT. */
        fun onOutputGrew()

        /** Hops to the EDT — a scan finishes on a pooled thread. */
        fun edt(block: () -> Unit)
    }

    private val log = Logger.getInstance(AgentScanner::class.java)

    /** Reads what each progress file has gained since the last poll. Owned here: nothing else tails. */
    private val outputTail = LiveOutputTail()

    /** Per-process, like everything else the binary re-announces after a restart. */
    fun clearTails() = outputTail.clear()

    /**
     * One scan at a time, and never a lost request.
     *
     * A scan walks a directory and reads files, so two at once is wasted IO; but DROPPING a request that
     * arrives mid-scan is worse, and it is what left a restored chat with no rows at all — the request that
     * would have found them landed while the first scan was still walking.
     */
    private val inFlight = AtomicBoolean(false)
    private val rescanRequested = AtomicBoolean(false)

    fun scan() {
        if (!inFlight.compareAndSet(false, true)) {
            rescanRequested.set(true)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            // Logged, not just swallowed. A failing scan means the agent rows silently stop updating, which
            // from outside is indistinguishable from "no agents ran" — the one failure mode this feature
            // must not have without a trace. The IO itself tolerates a missing directory on its own, so
            // anything reaching here is a real defect.
            val fresh = runCatching { agents.scan() }
                .onFailure { log.warn("agent scan failed; the agent rows will be stale", it) }
                .getOrDefault(emptyList())
            val tailed = runCatching { tailOutput() }
                .onFailure { log.warn("background-task tail failed; its output will be stale", it) }
                .getOrDefault(false)
            recordWhatIsOurs()
            inFlight.set(false)
            ui.edt {
                ui.labelCards()
                ui.onFresh(fresh)
                if (tailed) ui.onOutputGrew()
            }
            // Someone asked while this one was walking: honour it now, so a request made during a scan is
            // never silently lost.
            if (rescanRequested.compareAndSet(true, false)) scan()
        }
    }

    /**
     * Persists what was admitted, so a later run still counts these as ours while a terminal-spawned agent
     * in the same directory never does.
     *
     * Done here rather than at `task_started` because the `tool_use_id` → agent id mapping only exists once
     * the binary has written the sidecar.
     */
    private fun recordWhatIsOurs() {
        val id = sessionId() ?: return
        runCatching {
            val index = PluginAgentIndex.getInstance(project)
            // The whole shape, not just the id: parent, type and depth, so the record describes the tree it
            // is claiming instead of pointing at files and hoping.
            agents.nodes.values.forEach { index.admit(id, it) }
            // And the background tasks, which have no sidecar at all — this is the ONLY place the plugin can
            // say "this task was mine, and this agent ran it".
            tasks.all.forEach { task -> index.recordTask(id, task.taskId, task.toolUseId, ownerOfTask(task.taskId)) }
        }
    }

    /**
     * Just the tail, with none of the directory walking — what the live-output poll calls.
     *
     * A backgrounded shell command emits NOTHING between the `tool_result` that starts it and the
     * `task_notification` that ends it, so an event-driven tail read its output exactly once: at the end, by
     * which time the binary may already have taken the file away. Its card showed the command, the state and
     * the path, and never a line of what it printed. Polling is the only thing that can read a file that is
     * being written by another process with nobody announcing it.
     */
    fun tailNow() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val grew = runCatching { tailOutput() }
                .onFailure { log.warn("background-task tail failed; its output will be stale", it) }
                .getOrDefault(false)
            if (grew) ui.edt(ui::onOutputGrew)
        }
    }

    /**
     * Reads whatever each backgrounded agent's progress file has gained since the last scan.
     *
     * Only agents publish one; a backgrounded shell command publishes no file at all, and its output only
     * ever arrives as a tool result when the binary is asked for it. Rather than inventing a path for it,
     * the task's view says so.
     *
     * Blocking IO: runs inside the pooled scan, never on the EDT.
     */
    private fun tailOutput(): Boolean {
        var changed = false
        tasks.all.forEach { task ->
            val file = task.outputFile?.takeIf { it.isNotBlank() } ?: return@forEach
            val text = outputTail.readNew(Paths.get(file))
            if (text.isNotEmpty() && tasks.appendTailedOutput(task.taskId, text)) changed = true
        }
        return changed
    }

    /**
     * Brings back the agents a previous run of the plugin admitted for this session id, then scans.
     *
     * This is the whole of "restore the agent tabs": the transcripts are the binary's files, still on disk,
     * and the index says which of the agents in that directory were ever ours. An agent spawned from the
     * terminal is in the same directory and is never in the index, so it stays invisible.
     */
    fun restoreAdmitted(onTasksReplayed: () -> Unit) {
        val id = sessionId() ?: return
        // A restored chat admits what is in ITS OWN subagents directory. The index is a memory of what this
        // plugin witnessed live, and it is demonstrably incomplete — sessions on disk carry subagents whose
        // level-1 parent was never recorded — so relying on it alone brought chats back with no agents.
        agents.markRestored()
        ApplicationManager.getApplication().executeOnPooledThread {
            replayTasks(id, onTasksReplayed)
            val admitted = runCatching { PluginAgentIndex.getInstance(project).admittedAgents(id) }
                .getOrDefault(emptyList())
            // Debug rather than silent: a restore that produces no tabs throws nothing anywhere, so without
            // this number "my agents did not come back" is indistinguishable from "there were none". That is
            // not hypothetical — it is how the legacy-id mismatch was found (see AgentMeta.bareAgentId).
            log.debug("agent restore: session=$id indexed=${admitted.size}")
            admitted.takeIf { it.isNotEmpty() }?.let { agents.preAdmit(it) }
            scan()
        }
    }

    /**
     * Background tasks and their output, back from the binary's own transcript.
     *
     * The plugin records that a task was ours (and whose); the transcript records what it ran and what it
     * printed. Without this a restart came back with the agents and no tasks at all — no tabs, no commands,
     * and every line they had produced gone.
     *
     * Every task in THIS session's transcript is this chat's. The index used to filter them, and it is a
     * record of what the plugin saw LIVE — so anything started before the last restart, or in a run whose
     * index entry is thin, was dropped along with its command and its output. The transcript is per-session
     * and it is the binary's own: it cannot contain another chat's work, which is the only thing that filter
     * was ever protecting against.
     */
    private fun replayTasks(id: String, onReplayed: () -> Unit) {
        runCatching {
            val replayed = SessionStore.readLines(id)?.let { BackgroundTaskReplay.parse(it) }.orEmpty()
            if (tasks.seed(replayed)) ui.edt(onReplayed)
        }.onFailure { log.warn("could not replay background tasks for $id", it) }
    }
}
