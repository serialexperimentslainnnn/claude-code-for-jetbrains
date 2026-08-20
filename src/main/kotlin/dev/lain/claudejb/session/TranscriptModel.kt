package dev.lain.claudejb.session

import org.jetbrains.annotations.TestOnly
import java.util.concurrent.CopyOnWriteArrayList

enum class Speaker { USER, ASSISTANT, THINKING, TOOL, TOOL_OUTPUT, SYSTEM, ERROR, MEMORY }

enum class ToolState { LOADING, RUNNING, FINISHED, ERROR }

class TranscriptEntry(
    val id: Long,
    val speaker: Speaker,
    text: String,
    val meta: String? = null,
    val toolUseId: String? = null,
    val parentToolUseId: String? = null,
    toolState: ToolState = ToolState.FINISHED,
    val filePath: String? = null,
    val commandText: String? = null,
    val messageText: String? = null,
    val blockedRule: String? = null,
    /** The rule that matched on a call the guard let through anyway — an *Allow All* or a whitelist. */
    val bypassedRule: String? = null,
) {
    var text: String = text
        internal set

    var toolState: ToolState = toolState
        internal set

    var elapsedSeconds: Double = 0.0
        internal set

    var toolTitle: String? = null
        internal set

    var trimmed: Boolean = false
        internal set
}

class TranscriptModel {

    companion object {
        const val MAX_ENTRIES = 2000
    }

    interface Listener {
        fun onAdded(entry: TranscriptEntry, index: Int) {}
        fun onUpdated(entry: TranscriptEntry) {}
        fun onCleared() {}

        fun onTrimmed(removedIds: List<Long>, totalTrimmed: Int) {}
    }

    private val backing = ArrayList<TranscriptEntry>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private var nextId = 0L

    private val byToolUseId = HashMap<String, TranscriptEntry>()
    private val parentOf = HashMap<String, String>()

    val entries: List<TranscriptEntry> get() = backing

    var trimmedCount: Int = 0
        private set

    fun addListener(listener: Listener) = listeners.add(listener)
    fun removeListener(listener: Listener) = listeners.remove(listener)

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
        bypassedRule: String? = null,
    ): TranscriptEntry {
        val entry = TranscriptEntry(
            nextId++, speaker, text, meta, toolUseId, parentToolUseId, toolState, filePath, commandText,
            messageText, blockedRule, bypassedRule,
        )
        if (speaker == Speaker.TOOL && toolUseId != null) {
            byToolUseId[toolUseId] = entry
            if (parentToolUseId != null) parentOf[toolUseId] = parentToolUseId else parentOf.remove(toolUseId)
        }
        val index = insertionIndexFor(parentToolUseId)
        backing.add(index, entry)
        listeners.forEach { it.onAdded(entry, index) }
        trimToCap()
        return entry
    }

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

    fun addToolOutput(toolUseId: String, text: String, parentToolUseId: String? = null, meta: String? = null): TranscriptEntry {
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

    private fun insertionIndexFor(parent: String?): Int {
        if (parent == null) return backing.size
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

    fun toolNameOf(toolUseId: String): String? = byToolUseId[toolUseId]?.meta

    fun commandTextOf(toolUseId: String): String? = byToolUseId[toolUseId]?.commandText

    fun isCommandCall(toolUseId: String): Boolean = commandTextOf(toolUseId) != null

    fun setToolState(toolUseId: String, state: ToolState, elapsedSeconds: Double? = null) {
        val entry = byToolUseId[toolUseId] ?: return
        entry.toolState = state
        if (elapsedSeconds != null) entry.elapsedSeconds = elapsedSeconds
        listeners.forEach { it.onUpdated(entry) }
    }

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
