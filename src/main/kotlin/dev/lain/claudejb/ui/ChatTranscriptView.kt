package dev.lain.claudejb.ui

import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.EntryDTO
import dev.lain.claudejb.session.TranscriptEntry
import dev.lain.claudejb.session.TranscriptModel
import dev.lain.claudejb.ui.jcef.JcefBridge
import dev.lain.claudejb.ui.jcef.JcefTranscriptPayload
import javax.swing.Timer

internal class ChatTranscriptView(
    private val session: ClaudeSession,
    private val exec: (String) -> Unit,
) : TranscriptModel.Listener {

    private sealed interface Shown {
        object Chat : Shown
        data class Agent(val id: String) : Shown
        data class Task(val id: String) : Shown
    }

    private val dirty = LinkedHashSet<Long>()
    private var structural = false
    private val timer = Timer(ELAPSED_TICK_MS) { onTick() }.apply { isRepeats = true }

    private var shown: Shown = Shown.Chat

    private var lastRows: List<String> = emptyList()

    val showsTask: Boolean get() = shown is Shown.Task

    val showsChat: Boolean get() = shown is Shown.Chat

    fun showTranscript(agentId: String?) {
        show(agentId?.let { Shown.Agent(it) } ?: Shown.Chat)
    }

    fun showBackgroundTask(taskId: String) = show(Shown.Task(taskId))

    private fun show(next: Shown) {
        exec("window.cc.closeDashboard && window.cc.closeDashboard()")
        if (shown == next) return
        shown = next
        dirty.clear()
        lastRows = emptyList()
        exec("window.cc.clear && window.cc.clear()")
        when (next) {
            is Shown.Chat -> {
                exec("window.cc.clearAgentSelection && window.cc.clearAgentSelection()")
                fullResync()
                trimNotice(emptyList(), session.transcript.trimmedCount)
            }

            is Shown.Agent -> pushEntries(session.runningAgents.nodes[next.id]?.entries.orEmpty())

            is Shown.Task -> {
                exec("window.cc.revealTaskTab && window.cc.revealTaskTab(" + JcefBridge.jsString(next.id) + ")")
                pushEntries(BackgroundTaskView.entries(session, next.id), expanded = true)
            }
        }
    }

    fun refreshShown() {
        when (val current = shown) {
            is Shown.Chat -> Unit
            is Shown.Agent -> pushEntries(session.runningAgents.nodes[current.id]?.entries.orEmpty())
            is Shown.Task -> pushEntries(BackgroundTaskView.entries(session, current.id), expanded = true)
        }
    }

    private fun pushEntries(entries: List<EntryDTO>, expanded: Boolean = false) {
        val titles = HashMap<String, String>()
        val running = HashSet<String>()
        session.runningAgents.nodes.values.forEach { node ->
            val tool = node.meta.toolUseId ?: return@forEach
            node.meta.description?.takeIf { it.isNotBlank() }?.let { titles[tool] = "${node.kindLabel} ($it)" }
            if (node.status == dev.lain.claudejb.session.AgentStatus.RUNNING) running += tool
        }
        val ownerRunning = when (val current = shown) {
            is Shown.Agent -> session.runningAgents.nodes[current.id]?.status ==
                dev.lain.claudejb.session.AgentStatus.RUNNING

            is Shown.Task -> session.backgroundTaskRegistry.all.firstOrNull { it.taskId == current.id }?.running == true

            else -> false
        }
        val rows = JcefTranscriptPayload.agentRowsJson(entries, titles, running, expanded, ownerRunning)
        if (rows == lastRows) return
        if (rows.size < lastRows.size) {
            lastRows = rows
            exec("window.cc.clear && window.cc.clear()")
            if (rows.isNotEmpty()) exec("window.cc.batch && window.cc.batch([${rows.joinToString(",")}])")
            return
        }
        val changed = rows.filterIndexed { index, row -> index >= lastRows.size || lastRows[index] != row }
        lastRows = rows
        if (changed.isNotEmpty()) exec("window.cc.batch && window.cc.batch([${changed.joinToString(",")}])")
    }

    override fun onAdded(entry: TranscriptEntry, index: Int) {
        if (index < session.transcript.entries.size - 1) structural = true
        dirty.add(entry.id)
        ensureTimer()
    }

    override fun onUpdated(entry: TranscriptEntry) {
        dirty.add(entry.id)
        ensureTimer()
    }

    override fun onCleared() {
        dirty.clear()
        structural = false
        exec("window.cc.clear && window.cc.clear()")
    }

    override fun onTrimmed(removedIds: List<Long>, totalTrimmed: Int) {
        dirty.removeAll(removedIds.toSet())
        if (shown == Shown.Chat) trimNotice(removedIds, totalTrimmed)
    }

    private fun trimNotice(removedIds: List<Long>, totalTrimmed: Int) {
        val ids = removedIds.joinToString(",")
        exec("window.cc.trimRows && window.cc.trimRows({ids:[$ids],total:$totalTrimmed})")
    }

    private fun ensureTimer() {
        if (!timer.isRunning) timer.start()
    }

    private fun onTick() {
        if (shown != Shown.Chat) {
            dirty.clear()
            structural = true
            timer.stop()
            return
        }
        val entries = session.transcript.entries
        val items: List<Pair<TranscriptEntry, Int>> = if (structural) {
            structural = false
            entries.mapIndexed { index, entry -> entry to index }
        } else {
            val idToIndex = HashMap<Long, Int>(entries.size)
            entries.forEachIndexed { index, entry -> idToIndex[entry.id] = index }
            dirty.mapNotNull { id ->
                val idx = idToIndex[id] ?: return@mapNotNull null
                entries[idx] to idx
            }
        }
        dirty.clear()
        if (items.isNotEmpty()) {
            exec("window.cc.batch && window.cc.batch(" + JcefTranscriptPayload.batchJson(items) + ")")
        }
        if (!structural) timer.stop()
    }

    fun fullResync() {
        structural = true
        ensureTimer()
    }

    fun stop() = timer.stop()

    private companion object {
        const val ELAPSED_TICK_MS = 30
    }
}
