package dev.lain.claudejb.ui

import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.EntryDTO
import dev.lain.claudejb.session.TranscriptEntry
import dev.lain.claudejb.session.TranscriptModel
import dev.lain.claudejb.ui.jcef.JcefBridge
import dev.lain.claudejb.ui.jcef.JcefTranscriptPayload
import javax.swing.Timer

/**
 * What the ONE browser is painting — the chat's own transcript, an agent's, or a background task's view — and
 * the streaming coalescer that feeds it.
 *
 * Extracted from `JcefChatPanel`, which is an assembler. This owns one thing: the rows on screen. Streaming is
 * coalesced here: rapid transcript deltas accumulate a dirty-id set and a structural flag, drained by a 30ms
 * Swing timer into a single `cc.batch` frame per tick (the frontend upserts each row by id and repositions it
 * to its order), so the page never sees one DOM write per token.
 *
 * EDT-confined, like the panel itself: [TranscriptModel] fires its listeners there and the host delivers its
 * messages there.
 */
internal class ChatTranscriptView(
    private val session: ClaudeSession,
    /** Runs a snippet in the web view. */
    private val exec: (String) -> Unit,
) : TranscriptModel.Listener {

    /** What the single browser is painting. One type, so "an agent AND a task" cannot be represented. */
    private sealed interface Shown {
        object Chat : Shown
        data class Agent(val id: String) : Shown
        data class Task(val id: String) : Shown
    }

    // ── Streaming coalescer state (all touched on the EDT) ───────────────────────────────────────────────
    private val dirty = LinkedHashSet<Long>()
    private var structural = false
    private val timer = Timer(ELAPSED_TICK_MS) { onTick() }.apply { isRepeats = true }

    /**
     * Which transcript this browser is painting: the chat's own, an agent's, or a background task's view.
     *
     * One browser, many transcripts. A JCEF per agent tab would mean a Chromium process per agent, and the
     * session this feature exists for runs dozens at once.
     */
    private var shown: Shown = Shown.Chat

    /** The last payload sent by [pushEntries], so an unchanged repaint can be skipped. */
    private var lastPushed: String? = null

    /** True while a background task's view is on screen — the only view that has to grow on a state fire. */
    val showsTask: Boolean get() = shown is Shown.Task

    /**
     * Paints [agentId]'s transcript (null → the chat's own), replacing whatever is on screen.
     *
     * While an agent is shown, the chat's live rows are still tracked in the model but not pushed: the
     * frontend upserts by row id, so letting both streams write would interleave a live chat row into an
     * agent's transcript — the very mixing this release removes. Switching back re-sends the chat in full.
     */
    fun showTranscript(agentId: String?) {
        show(agentId?.let { Shown.Agent(it) } ?: Shown.Chat)
    }

    /**
     * Paints background task [taskId]: what it is, who started it, and whatever output has come back.
     *
     * A task has no transcript — it is a process, not a conversation — so this is built from what the binary
     * reported about it. It is deliberately NOT its owner's transcript: sending the user there is what made
     * clicking a task's tab look broken.
     */
    fun showBackgroundTask(taskId: String) = show(Shown.Task(taskId))

    private fun show(next: Shown) {
        // Whatever the transcript is about to become, it lives in the chat area — so leave the dashboard if
        // it is covering it. Selecting a tab used to repaint behind an open panel, which reads as the click
        // doing nothing at all.
        exec("window.cc.closeDashboard && window.cc.closeDashboard()")
        if (shown == next) return
        shown = next
        dirty.clear()
        lastPushed = null // a different thing is being shown; the skip-if-unchanged guard must not hold it back
        exec("window.cc.clear && window.cc.clear()")
        when (next) {
            is Shown.Chat -> {
                // Nothing in the rows is current any more, and the bar has to say so — otherwise a pill stays
                // highlighted for a transcript that is no longer on screen.
                exec("window.cc.clearAgentSelection && window.cc.clearAgentSelection()")
                fullResync()
                // `cc.clear()` above took the trimmed-rows notice with it, and a resend only carries rows that
                // still exist — so the count has to be re-asserted or the page silently claims nothing was
                // dropped. An empty id list is a pure notice update.
                trimNotice(emptyList(), session.transcript.trimmedCount)
            }

            is Shown.Agent -> pushEntries(session.runningAgents.nodes[next.id]?.entries.orEmpty())

            is Shown.Task -> {
                // Tell the BAR too, or the view is painted with no pill for it: the click reads as having
                // done nothing, and there is then no pill to click to get back to the chat.
                exec("window.cc.revealTaskTab && window.cc.revealTaskTab(" + JcefBridge.jsString(next.id) + ")")
                pushEntries(BackgroundTaskView.entries(session, next.id), expanded = true)
            }
        }
    }

    /** Re-sends whatever is shown besides the chat, after a scan or a state change. */
    fun refreshShown() {
        when (val current = shown) {
            is Shown.Chat -> Unit

            is Shown.Agent -> pushEntries(session.runningAgents.nodes[current.id]?.entries.orEmpty())

            // The point of the task view: its output grows while you are looking at it.
            is Shown.Task -> pushEntries(BackgroundTaskView.entries(session, current.id), expanded = true)
        }
    }

    /**
     * Paints a reconstructed transcript (an agent's, or a background task's view).
     *
     * **Skips the repaint when nothing changed**, and that is not an optimisation: this is called on every
     * state fire so a task's output can grow while you watch it, and clearing the page to re-send identical
     * rows several times a turn is exactly the flicker the user saw.
     */
    private fun pushEntries(entries: List<EntryDTO>, expanded: Boolean = false) {
        // Agent labels and in-flight calls, so a card inside an agent's transcript reads and behaves like one
        // in the chat: `Agent (…)` / `Subagent (…)`, and still fading while its agent works.
        val titles = HashMap<String, String>()
        val running = HashSet<String>()
        session.runningAgents.nodes.values.forEach { node ->
            val tool = node.meta.toolUseId ?: return@forEach
            node.meta.description?.takeIf { it.isNotBlank() }?.let { titles[tool] = "${node.kindLabel} ($it)" }
            if (node.status == dev.lain.claudejb.session.AgentStatus.RUNNING) running += tool
        }
        // Is the thing whose transcript is on screen still working? A call with no result is only in flight
        // while something can still return it; in a stopped agent's transcript it was cut off.
        val ownerRunning = when (val current = shown) {
            is Shown.Agent -> session.runningAgents.nodes[current.id]?.status ==
                dev.lain.claudejb.session.AgentStatus.RUNNING

            is Shown.Task -> session.backgroundTaskRegistry.all.firstOrNull { it.taskId == current.id }?.running == true

            else -> false
        }
        val payload =
            if (entries.isEmpty()) {
                ""
            } else {
                JcefTranscriptPayload.agentBatchJson(entries, titles, running, expanded, ownerRunning)
            }
        if (payload == lastPushed) return
        lastPushed = payload
        exec("window.cc.clear && window.cc.clear()")
        if (payload.isNotEmpty()) {
            exec("window.cc.batch && window.cc.batch($payload)")
        }
    }

    // ── TranscriptModel.Listener ─────────────────────────────────────────────────────────────────────────

    override fun onAdded(entry: TranscriptEntry, index: Int) {
        // Append-at-tail (the common streaming case) leaves every existing row's order unchanged, so we only need
        // to send the NEW row (the dirty path, same as a streaming text update) instead of re-serializing the
        // whole transcript on every added row — the previous unconditional `structural = true` was O(N²) across a
        // turn and made the transcript visibly flicker. A middle insert shifts following rows' orders, so it still
        // needs a full structural resend.
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

    /**
     * The oldest rows left the model. Two things have to happen, in this order and in this one EDT call:
     *
     *  - drop them from [dirty], or the next tick would push a row that no longer exists and the page would
     *    upsert it straight back in;
     *  - tell the page, so it removes those nodes and shows how many rows the cap has dropped in total.
     *
     * The second half only matters while the CHAT is on screen: an agent's or a task's view does not paint the
     * chat's rows, so there is nothing there to remove. Pruning [dirty] is unconditional.
     */
    override fun onTrimmed(removedIds: List<Long>, totalTrimmed: Int) {
        dirty.removeAll(removedIds.toSet())
        if (shown == Shown.Chat) trimNotice(removedIds, totalTrimmed)
    }

    /** `cc.trimRows`: removes [removedIds] from the page and states the cumulative [totalTrimmed]. */
    private fun trimNotice(removedIds: List<Long>, totalTrimmed: Int) {
        val ids = removedIds.joinToString(",")
        exec("window.cc.trimRows && window.cc.trimRows({ids:[$ids],total:$totalTrimmed})")
    }

    private fun ensureTimer() {
        if (!timer.isRunning) timer.start()
    }

    /** Coalescer tick (EDT): one `cc.batch` frame — all rows on a structural change, else just the dirty ones. */
    private fun onTick() {
        // An agent's transcript (or a task's view) is on screen: keep coalescing the chat's rows into the
        // model, but do not paint them over it. They are re-sent whole when the user switches back.
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
        // Nothing left to coalesce: `dirty` was just drained, and a structural resend is the only thing that can
        // still be outstanding. The timer restarts on the next delta ([ensureTimer]), so an idle chat costs no ticks.
        if (!structural) timer.stop()
    }

    /** Force a full transcript resend on the next tick (used on init and on a late page `Ready`). */
    fun fullResync() {
        structural = true
        ensureTimer()
    }

    /** Stops the coalescer. Called from the panel's `dispose`; nothing restarts it. */
    fun stop() = timer.stop()

    private companion object {
        /** Tick driving the tool cards' live elapsed counters. ~33 fps: smooth, and the work per tick is trivial. */
        const val ELAPSED_TICK_MS = 30
    }
}
