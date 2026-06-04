package dev.lain.claudejb.session

import java.util.concurrent.CopyOnWriteArrayList

/** Who produced a transcript entry; drives styling in the chat panel. */
enum class Speaker { USER, ASSISTANT, THINKING, TOOL, TOOL_OUTPUT, SYSTEM, ERROR, MEMORY }

/**
 * Lifecycle of a tool call, reflected on its box: [LOADING] just dispatched (light blue), [RUNNING] actively
 * executing — a tool_progress heartbeat arrived (amber), [FINISHED] its result landed (green). Restored history
 * rows default to [FINISHED]. The protocol carries no completion %, so RUNNING surfaces elapsed time instead.
 */
enum class ToolState { LOADING, RUNNING, FINISHED }

/** One renderable line of the conversation. [text] is mutable so streaming deltas can grow an entry in place. */
class TranscriptEntry(
    val id: Long,
    val speaker: Speaker,
    text: String,
    /** Secondary label, e.g. a tool name or a file path. */
    val meta: String? = null,
    /** Links a tool call and its output so the output renders anchored to its call, not at the tail. */
    val toolUseId: String? = null,
    /** Set when this entry belongs to a subagent (Task): the Agent's tool_use id it nests under. */
    val parentToolUseId: String? = null,
    toolState: ToolState = ToolState.FINISHED,
) {
    var text: String = text
        internal set

    /** Tool-call lifecycle state (only meaningful for [Speaker.TOOL] entries); drives the box colour. */
    var toolState: ToolState = toolState
        internal set

    /** Elapsed execution time (seconds) from the latest tool_progress; shown while [RUNNING] (no % exists). */
    var elapsedSeconds: Double = 0.0
        internal set
}

/**
 * Observable list of [TranscriptEntry]. All mutation and notification happens on the EDT (the session
 * marshals events there), so listeners — the Swing chat panel — can update components directly.
 */
class TranscriptModel {

    interface Listener {
        fun onAdded(entry: TranscriptEntry, index: Int) {}
        fun onUpdated(entry: TranscriptEntry) {}
        fun onCleared() {}
    }

    private val backing = ArrayList<TranscriptEntry>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private var nextId = 0L

    /** Hierarchy source of truth: tool_use id → its TOOL entry, and tool_use id → its parent Agent's id. */
    private val byToolUseId = HashMap<String, TranscriptEntry>()
    private val parentOf = HashMap<String, String>()

    val entries: List<TranscriptEntry> get() = backing

    fun addListener(listener: Listener) = listeners.add(listener)
    fun removeListener(listener: Listener) = listeners.remove(listener)

    /** The Agent tool_use id that [toolUseId] nests under, or null if it is top-level. */
    fun parentToolOf(toolUseId: String): String? = parentOf[toolUseId]

    fun add(
        speaker: Speaker,
        text: String,
        meta: String? = null,
        toolUseId: String? = null,
        parentToolUseId: String? = null,
        toolState: ToolState = ToolState.FINISHED,
    ): TranscriptEntry {
        val entry = TranscriptEntry(nextId++, speaker, text, meta, toolUseId, parentToolUseId, toolState)
        if (speaker == Speaker.TOOL && toolUseId != null) {
            byToolUseId[toolUseId] = entry
            if (parentToolUseId != null) parentOf[toolUseId] = parentToolUseId
        }
        val index = insertionIndexFor(parentToolUseId)
        backing.add(index, entry)
        listeners.forEach { it.onAdded(entry, index) }
        return entry
    }

    /**
     * Adds a tool output anchored to its [toolUseId]: inserts it right after the matching tool call (and any
     * outputs already attached to it), so parallel tool calls don't scatter their outputs at the transcript tail.
     * Inherits the call's [parentToolUseId] so subagent outputs nest under their Agent. Falls back to appending
     * if the call isn't found.
     */
    fun addToolOutput(toolUseId: String, text: String, parentToolUseId: String? = null, meta: String? = null): TranscriptEntry {
        // O(1) lookup of the TOOL entry via byToolUseId; indexOf is a reference-equality scan (cheaper than the
        // former per-element toolUseId string compare). byToolUseId holds exactly the entry indexOfLast would find.
        // Duplicate-id safety: on resume/fork replay the binary may re-emit a tool_use_id, in which case the map
        // holds the LAST TranscriptEntry put for it; the output simply anchors under that current call. If the
        // mapped entry isn't (or no longer is) in backing, indexOf returns -1 and we degrade to appending at the
        // tail — never an out-of-bounds index. Same guarantee in insertionIndexFor below.
        val toolEntry = byToolUseId[toolUseId]
        val toolIdx = if (toolEntry != null) backing.indexOf(toolEntry) else -1
        val parent = parentToolUseId ?: toolEntry?.parentToolUseId
        val insertAt = if (toolIdx < 0) backing.size else {
            var i = toolIdx + 1
            while (i < backing.size && backing[i].speaker == Speaker.TOOL_OUTPUT && backing[i].toolUseId == toolUseId) i++
            i
        }
        val entry = TranscriptEntry(nextId++, Speaker.TOOL_OUTPUT, text, meta, toolUseId, parent)
        backing.add(insertAt, entry)
        listeners.forEach { it.onAdded(entry, insertAt) }
        return entry
    }

    /** Whether [child] is [ancestor] or sits somewhere below it in the tool hierarchy (cycle-guarded). */
    private fun isDescendantOf(child: String?, ancestor: String): Boolean {
        var cur = child
        val seen = HashSet<String>()
        while (cur != null && seen.add(cur)) {
            if (cur == ancestor) return true
            cur = parentOf[cur]
        }
        return false
    }

    private fun belongsToSubtree(e: TranscriptEntry, parent: String): Boolean =
        e.toolUseId == parent || isDescendantOf(e.toolUseId, parent) || isDescendantOf(e.parentToolUseId, parent)

    /**
     * Where to insert a new entry so each tool's subtree stays contiguous: at the end of [parent]'s block
     * (the Agent's TOOL row plus every descendant already attached). Appends at the tail for top-level entries
     * or when the parent isn't found.
     */
    private fun insertionIndexFor(parent: String?): Int {
        if (parent == null) return backing.size
        // Same O(1) lookup + reference-equality scan as addToolOutput: byToolUseId[parent] is the parent's TOOL row.
        val parentEntry = byToolUseId[parent] ?: return backing.size
        val anchor = backing.indexOf(parentEntry)
        if (anchor < 0) return backing.size
        var i = anchor + 1
        while (i < backing.size && belongsToSubtree(backing[i], parent)) i++
        return i
    }

    fun append(entry: TranscriptEntry, delta: String) {
        entry.text += delta
        listeners.forEach { it.onUpdated(entry) }
    }

    fun replaceText(entry: TranscriptEntry, text: String) {
        entry.text = text
        listeners.forEach { it.onUpdated(entry) }
    }

    /** Update a tool call's lifecycle [state] (and optional [elapsedSeconds]) by its [toolUseId], then notify. */
    fun setToolState(toolUseId: String, state: ToolState, elapsedSeconds: Double? = null) {
        val entry = byToolUseId[toolUseId] ?: return
        entry.toolState = state
        if (elapsedSeconds != null) entry.elapsedSeconds = elapsedSeconds
        listeners.forEach { it.onUpdated(entry) }
    }

    fun clear() {
        backing.clear()
        byToolUseId.clear()
        parentOf.clear()
        listeners.forEach { it.onCleared() }
    }
}
