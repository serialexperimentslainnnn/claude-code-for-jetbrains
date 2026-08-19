package dev.lain.claudejb.session

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

class AgentScanner(
    private val project: Project,
    private val agents: AgentRegistry,
    private val tasks: BackgroundTaskRegistry,
    private val sessionId: () -> String?,
    private val ownerOfTask: (String) -> String?,
    private val ui: Ui,
) {

    interface Ui {
        fun labelCards()

        fun onFresh(fresh: List<String>)

        fun onOutputGrew()

        fun edt(block: () -> Unit)
    }

    private val log = Logger.getInstance(AgentScanner::class.java)

    private val outputTail = LiveOutputTail()

    fun clearTails() = outputTail.clear()

    private val inFlight = AtomicBoolean(false)
    private val rescanRequested = AtomicBoolean(false)

    fun scan() {
        if (!inFlight.compareAndSet(false, true)) {
            rescanRequested.set(true)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
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
            if (rescanRequested.compareAndSet(true, false)) scan()
        }
    }

    private fun recordWhatIsOurs() {
        val id = sessionId() ?: return
        runCatching {
            val index = PluginAgentIndex.getInstance(project)
            agents.nodes.values.forEach { index.admit(id, it) }
            tasks.all.forEach { task -> index.recordTask(id, task.taskId, task.toolUseId, ownerOfTask(task.taskId)) }
        }
    }

    fun tailNow() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val grew = runCatching { tailOutput() }
                .onFailure { log.warn("background-task tail failed; its output will be stale", it) }
                .getOrDefault(false)
            if (grew) ui.edt(ui::onOutputGrew)
        }
    }

    private fun tailOutput(): Boolean {
        var changed = false
        tasks.all.forEach { task ->
            val file = task.outputFile?.takeIf { it.isNotBlank() } ?: return@forEach
            val text = outputTail.readNew(Paths.get(file))
            if (text.isNotEmpty() && tasks.appendTailedOutput(task.taskId, text)) changed = true
        }
        return changed
    }

    fun restoreAdmitted(onTasksReplayed: () -> Unit) {
        val id = sessionId() ?: return
        agents.markRestored()
        ApplicationManager.getApplication().executeOnPooledThread {
            replayTasks(id, onTasksReplayed)
            val admitted = runCatching { PluginAgentIndex.getInstance(project).admittedAgents(id) }
                .getOrDefault(emptyList())
            log.debug("agent restore: session=$id indexed=${admitted.size}")
            admitted.takeIf { it.isNotEmpty() }?.let { agents.preAdmit(it) }
            scan()
        }
    }

    private fun replayTasks(id: String, onReplayed: () -> Unit) {
        runCatching {
            val replayed = SessionStore.readLines(id)?.let { BackgroundTaskReplay.parse(it) }.orEmpty()
            if (tasks.seed(replayed)) ui.edt(onReplayed)
        }.onFailure { log.warn("could not replay background tasks for $id", it) }
    }
}
