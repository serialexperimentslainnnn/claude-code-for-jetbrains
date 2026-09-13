package dev.lain.claudejb.model.session.turn

import dev.lain.claudejb.model.protocol.ClaudeEvent
import dev.lain.claudejb.model.session.transcript.TranscriptReconciler

class StreamBuffer {

    private val lock = Any()
    private val runs = ArrayList<Pair<Boolean, StringBuilder>>()
    private var usage: IntArray? = null

    fun buffer(event: ClaudeEvent.Stream) {
        when (event) {
            is ClaudeEvent.TextDelta ->
                if (TranscriptReconciler.belongsHere(event.parentToolUseId)) append(isThinking = false, event.text)

            is ClaudeEvent.ThinkingDelta ->
                if (TranscriptReconciler.belongsHere(event.parentToolUseId)) append(isThinking = true, event.text)

            is ClaudeEvent.LiveUsage -> synchronized(lock) {
                usage = intArrayOf(event.inputTokens, event.cacheCreationTokens, event.cacheReadTokens, event.outputTokens)
            }
        }
    }

    private fun append(isThinking: Boolean, text: String) = synchronized(lock) {
        val last = runs.lastOrNull()
        if (last != null && last.first == isThinking) {
            last.second.append(text)
        } else {
            runs.add(isThinking to StringBuilder(text))
        }
    }

    fun drain(): Drained? {
        synchronized(lock) {
            if (runs.isEmpty() && usage == null) return null
            val out = Drained(runs.map { it.first to it.second.toString() }, usage)
            runs.clear()
            usage = null
            return out
        }
    }

    class Drained(val runs: List<Pair<Boolean, String>>, val usage: IntArray?)
}
