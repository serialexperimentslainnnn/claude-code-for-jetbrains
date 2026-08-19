package dev.lain.claudejb.session

import org.jetbrains.annotations.TestOnly
import java.util.concurrent.CopyOnWriteArrayList

/** Who produced a transcript entry; drives styling in the chat panel. */
enum class Speaker { USER, ASSISTANT, THINKING, TOOL, TOOL_OUTPUT, SYSTEM, ERROR, MEMORY }

/**
 * Lifecycle of a tool call, reflected on its box: [LOADING] just dispatched (light blue), [RUNNING] actively
 * executing — a tool_progress heartbeat arrived (amber), [FINISHED] its result landed (green). Restored history
 * rows default to [FINISHED]. The protocol carries no completion %, so RUNNING surfaces elapsed time instead.
 */
enum class ToolState { LOADING, RUNNING, FINISHED, ERROR }

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
    /**
     * For a file tool (Read/Edit/Write/…): the file it acts on, **relative to the project root**. Drives the
     * transcript's jump-to-code link on the tool card. Null on every other row. Kept relative on purpose — the web
     * view never needs the user's absolute paths; the host resolves it against the root (and gates on it).
     */
    val filePath: String? = null,
    /**
     * Set on a [Speaker.TOOL] row whose call executes a command (`Bash`, or any tool — including MCP ones —
     * whose input carries a command/script argument) to the raw command/script text; see
     * [dev.lain.claudejb.permission.ToolInputScanner.commandText]. Drives the command's own copyable code block in
     * the tool card, and is looked up later, when that call's [Speaker.TOOL_OUTPUT] arrives, to decide whether to
     * render its output as a copyable code block rather than plain text. Null on every other row.
     */
    val commandText: String? = null,
    /**
     * Set on a [Speaker.TOOL] row whose call SENDS text — a message to another agent, a prompt, a question —
     * to that text; see [dev.lain.claudejb.permission.ToolInputScanner.messageText]. Drives the card's own
     * message block, which sits outside the collapse toggle for the same reason the command does: a card
     * showing only `{"success":true,…}` says the call worked and never says what was said. Null otherwise.
     */
    val messageText: String? = null,
    /**
     * Set on the [Speaker.SYSTEM] row that reports a security guard BLOCK, to the
     * [dev.lain.claudejb.permission.SecurityRule] name that refused the call. Null on every other row.
     *
     * It is what makes the block actionable rather than a dead end: the row draws a *Disable rule* link, and the
     * link has to know which rule it would open. The rule NAME travels rather than its prose, so the control
     * keeps working when the wording of a message changes — the same reason
     * [dev.lain.claudejb.permission.GuardAlert] carries the enum.
     */
    val blockedRule: String? = null,
) {
    var text: String = text
        internal set

    /** Tool-call lifecycle state (only meaningful for [Speaker.TOOL] entries); drives the box colour. */
    var toolState: ToolState = toolState
        internal set

    /** Elapsed execution time (seconds) from the latest tool_progress; shown while [RUNNING] (no % exists). */
    var elapsedSeconds: Double = 0.0
        internal set

    /**
     * A better label for this row, when one becomes known LATER than the row itself.
     *
     * The case it exists for: an `Agent` card says only "Agent" when it is created, because the description
     * of what that agent is doing is written by the binary into the agent's own sidecar — which appears
     * after the call. Once the scan has read it, the card can say `Agent (Inventory of dependencies)`.
     *
     * Deliberately NOT [meta]: that field is the tool's NAME and is compared against it all over the place
     * (reviewable tools, command detection, the icon). Renaming it to make a card read better would break
     * every one of those comparisons silently.
     */
    var toolTitle: String? = null
        internal set

    /**
     * True once this entry has been dropped by [TranscriptModel]'s memory cap. Read by
     * [TranscriptReconciler], which holds pointers to the entry deltas are still growing: appending to an
     * entry that is no longer in the model would silently lose the text.
     */
    var trimmed: Boolean = false
        internal set
}

/**
 * Observable list of [TranscriptEntry]. All mutation and notification happens on the EDT (the session
 * marshals events there), so listeners — the Swing chat panel — can update components directly.
 *
 * The list is bounded: past [MAX_ENTRIES] the OLDEST rows are dropped and listeners are told how many went,
 * so the UI can say so. Only the head is ever dropped — the tail carries the live streaming block and is
 * where the user is looking.
 */
class TranscriptModel {

    companion object {
        /**
         * Most rows kept in memory. Derived from measurement, not taste: the longest real session recorded on
         * this machine reached ~5 000 rows and 4.4 MB of transcript text, at a mean of ~870 chars per row. 2 000
         * rows is ~1.7 MB of text here and roughly three times that in the web view, which holds the same text as
         * DOM, as raw-text cache and as row record — so this is the point where the page stops being the dominant
         * cost while still keeping far more scrollback than a session's recent work needs.
         *
         * Trimming is a MEMORY bound, never data loss: the binary's own session file on disk keeps the whole
         * conversation, and "Open Previous Session…" reads it back in full.
         */
        const val MAX_ENTRIES = 2000
    }

    interface Listener {
        fun onAdded(entry: TranscriptEntry, index: Int) {}
        fun onUpdated(entry: TranscriptEntry) {}
        fun onCleared() {}

        /**
         * The oldest rows were dropped to stay under [MAX_ENTRIES]. [removedIds] are the entry ids that just
         * went; [totalTrimmed] is the cumulative count since this model was created or last cleared.
         */
        fun onTrimmed(removedIds: List<Long>, totalTrimmed: Int) {}
    }

    private val backing = ArrayList<TranscriptEntry>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private var nextId = 0L

    /** Hierarchy source of truth: tool_use id → its TOOL entry, and tool_use id → its parent Agent's id. */
    private val byToolUseId = HashMap<String, TranscriptEntry>()
    private val parentOf = HashMap<String, String>()

    val entries: List<TranscriptEntry> get() = backing

    /** How many rows the memory cap has dropped since this model was created or last [clear]ed. */
    var trimmedCount: Int = 0
        private set

    fun addListener(listener: Listener) = listeners.add(listener)
    fun removeListener(listener: Listener) = listeners.remove(listener)

    /**
     * The Agent tool_use id that [toolUseId] nests under, or null if it is top-level.
     *
     * The observation seam for [parentOf], which is production state — the nesting itself is used internally
     * ([isDescendantOf]) and the map is pruned at the cap. Nothing renders from this.
     */
    @TestOnly
    fun parentToolOf(toolUseId: String): String? = parentOf[toolUseId]

    fun add(
        speaker: Speaker,
        text: String,
        meta: String? = null,
        toolUseId: String? = null,
        parentToolUseId: String? = null,
        toolState: ToolState = ToolState.FINISHED,
        filePath: String? = null,
        commandText: String? = null,
        messageText: String? = null,
        blockedRule: String? = null,
    ): TranscriptEntry {
        val entry = TranscriptEntry(
            nextId++, speaker, text, meta, toolUseId, parentToolUseId, toolState, filePath, commandText,
            messageText, blockedRule,
        )
        if (speaker == Speaker.TOOL && toolUseId != null) {
            byToolUseId[toolUseId] = entry
            // Both maps describe the SAME row, so both are rewritten together. A replayed call can come back
            // with a different shape — nested under an Agent the first time, top-level the second — and a
            // parent left over from the previous row would keep nesting the id: parentToolOf would name an
            // Agent this row does not belong to, and insertionIndexFor would place later rows inside it.
            if (parentToolUseId != null) parentOf[toolUseId] = parentToolUseId else parentOf.remove(toolUseId)
        }
        val index = insertionIndexFor(parentToolUseId)
        backing.add(index, entry)
        listeners.forEach { it.onAdded(entry, index) }
        trimToCap()
        return entry
    }

    /**
     * Drops rows from the head until the list is within [MAX_ENTRIES], marks each dropped entry
     * [TranscriptEntry.trimmed] and notifies once for the whole pass. Nothing is fired when nothing was dropped.
     *
     * Also prunes [byToolUseId] and [parentOf] of the dropped calls — those maps live as long as the session, so
     * leaving them to grow would move the leak rather than close it. A mapping is only removed when it still
     * points at the entry being dropped: a resume/fork replay can re-emit a `tool_use_id`, and the map then holds
     * a NEWER live row that must keep resolving.
     */
    private fun trimToCap() {
        if (backing.size <= MAX_ENTRIES) return
        val removedIds = ArrayList<Long>(backing.size - MAX_ENTRIES)
        while (backing.size > MAX_ENTRIES) {
            val removed = backing.removeAt(0)
            removed.trimmed = true
            removedIds += removed.id
            val toolUseId = removed.toolUseId
            if (toolUseId != null && byToolUseId[toolUseId] === removed) {
                byToolUseId.remove(toolUseId)
                parentOf.remove(toolUseId)
            }
        }
        trimmedCount += removedIds.size
        listeners.forEach { it.onTrimmed(removedIds, trimmedCount) }
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
        val insertAt = if (toolIdx < 0) {
            backing.size
        } else {
            var i = toolIdx + 1
            while (i < backing.size && backing[i].speaker == Speaker.TOOL_OUTPUT && backing[i].toolUseId == toolUseId) i++
            i
        }
        val entry = TranscriptEntry(nextId++, Speaker.TOOL_OUTPUT, text, meta, toolUseId, parent)
        backing.add(insertAt, entry)
        listeners.forEach { it.onAdded(entry, insertAt) }
        trimToCap()
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

    /** The tool's name for a `tool_use_id` (the TOOL row's `meta`), or null when the call is unknown here. */
    fun toolNameOf(toolUseId: String): String? = byToolUseId[toolUseId]?.meta

    /** The raw command text behind `tool_use_id`, or null when it isn't a command call — see [TranscriptEntry.commandText]. */
    fun commandTextOf(toolUseId: String): String? = byToolUseId[toolUseId]?.commandText

    /** True when the call behind `tool_use_id` executes a command — see [TranscriptEntry.commandText]. */
    fun isCommandCall(toolUseId: String): Boolean = commandTextOf(toolUseId) != null

    /** Update a tool call's lifecycle [state] (and optional [elapsedSeconds]) by its [toolUseId], then notify. */
    fun setToolState(toolUseId: String, state: ToolState, elapsedSeconds: Double? = null) {
        val entry = byToolUseId[toolUseId] ?: return
        entry.toolState = state
        if (elapsedSeconds != null) entry.elapsedSeconds = elapsedSeconds
        listeners.forEach { it.onUpdated(entry) }
    }

    /**
     * Gives the call behind [toolUseId] a better label (see [TranscriptEntry.toolTitle]). Returns true when
     * it actually changed, so the caller can avoid a repaint per scan.
     */
    fun setToolTitle(toolUseId: String, title: String): Boolean {
        val entry = byToolUseId[toolUseId] ?: return false
        if (entry.toolTitle == title) return false
        entry.toolTitle = title
        listeners.forEach { it.onUpdated(entry) }
        return true
    }

    fun clear() {
        backing.clear()
        byToolUseId.clear()
        parentOf.clear()
        trimmedCount = 0
        listeners.forEach { it.onCleared() }
    }
}
