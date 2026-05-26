package dev.lain.claudejb.session

import java.util.concurrent.CopyOnWriteArrayList

/** Who produced a transcript entry; drives styling in the chat panel. */
enum class Speaker { USER, ASSISTANT, THINKING, TOOL, SYSTEM, ERROR }

/** One renderable line of the conversation. [text] is mutable so streaming deltas can grow an entry in place. */
class TranscriptEntry(
    val id: Long,
    val speaker: Speaker,
    text: String,
    /** Secondary label, e.g. a tool name or a file path. */
    val meta: String? = null,
) {
    var text: String = text
        internal set
}

/**
 * Observable list of [TranscriptEntry]. All mutation and notification happens on the EDT (the session
 * marshals events there), so listeners — the Swing chat panel — can update components directly.
 */
class TranscriptModel {

    interface Listener {
        fun onAdded(entry: TranscriptEntry) {}
        fun onUpdated(entry: TranscriptEntry) {}
        fun onCleared() {}
    }

    private val backing = ArrayList<TranscriptEntry>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private var nextId = 0L

    val entries: List<TranscriptEntry> get() = backing

    fun addListener(listener: Listener) = listeners.add(listener)
    fun removeListener(listener: Listener) = listeners.remove(listener)

    fun add(speaker: Speaker, text: String, meta: String? = null): TranscriptEntry {
        val entry = TranscriptEntry(nextId++, speaker, text, meta)
        backing.add(entry)
        listeners.forEach { it.onAdded(entry) }
        return entry
    }

    fun append(entry: TranscriptEntry, delta: String) {
        entry.text += delta
        listeners.forEach { it.onUpdated(entry) }
    }

    fun replaceText(entry: TranscriptEntry, text: String) {
        entry.text = text
        listeners.forEach { it.onUpdated(entry) }
    }

    fun clear() {
        backing.clear()
        listeners.forEach { it.onCleared() }
    }
}
