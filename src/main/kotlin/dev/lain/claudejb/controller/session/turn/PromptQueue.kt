package dev.lain.claudejb.controller.session.turn

import dev.lain.claudejb.model.protocol.control.ControlProtocol
import dev.lain.claudejb.model.session.transcript.Speaker
import dev.lain.claudejb.model.session.transcript.TranscriptModel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PromptQueue(
    private val transcript: TranscriptModel,
    private val edt: (() -> Unit) -> Unit,
    private val write: (String) -> Boolean,
    private val canSend: () -> Boolean,
    private val onSent: () -> Unit,
    private val fireState: () -> Unit,
) {

    private data class Outgoing(val text: String, val images: List<Pair<String, String>>, val displayText: String)

    private val queue = ArrayDeque<Outgoing>()

    private val toolUseTurn = ConcurrentHashMap<String, String>()

    @Volatile var currentUserMessageId: String? = null
        private set

    @Volatile var suggestion: String? = null
        private set

    fun queued(): List<String> = queue.map { it.displayText }

    fun userMessageIdFor(toolUseId: String): String? = toolUseTurn[toolUseId]

    fun bindTool(toolUseId: String) {
        currentUserMessageId?.let { toolUseTurn[toolUseId] = it }
    }

    fun enqueue(text: String, images: List<Pair<String, String>>, displayText: String) = edt {
        queue.addLast(Outgoing(text, images, displayText))
        fireState()
        pump()
    }

    fun remove(index: Int) = edt {
        if (index in queue.indices) {
            queue.removeAt(index)
            fireState()
        }
    }

    fun pump() {
        while (canSend() && queue.isNotEmpty()) {
            val next = queue.first()
            val msgUuid = UUID.randomUUID().toString()
            if (!write(ControlProtocol.userMessageWithImages(next.text, next.images, uuid = msgUuid))) return
            queue.removeFirst()
            transcript.add(Speaker.USER, next.displayText)
            currentUserMessageId = msgUuid
            onSent()
            dropSuggestion()
            fireState()
        }
    }

    fun dropSuggestion() {
        suggestion = null
    }

    fun suggest(text: String) {
        suggestion = text.takeIf { it.isNotBlank() }
        edt { fireState() }
    }

    fun clearSuggestion() {
        if (suggestion == null) return
        suggestion = null
        edt { fireState() }
    }

    fun clear() = queue.clear()

    fun forgetTurn() {
        toolUseTurn.clear()
        currentUserMessageId = null
    }
}
