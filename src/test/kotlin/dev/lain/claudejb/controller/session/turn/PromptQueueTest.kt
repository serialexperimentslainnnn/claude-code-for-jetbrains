package dev.lain.claudejb.controller.session.turn

import dev.lain.claudejb.model.session.transcript.TranscriptModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PromptQueueTest {

    private val transcript = TranscriptModel()
    private val written = mutableListOf<String>()
    private var ready = true
    private var writeSucceeds = true
    private var stateFired = 0

    private val queue = PromptQueue(
        transcript = transcript,
        edt = { it() },
        write = { line ->
            written.add(line)
            writeSucceeds
        },
        canSend = { ready },
        onSent = {},
        fireState = { stateFired++ },
    )

    private fun sessionReady() {
        ready = true
        queue.pump()
    }

    @Test
    fun `a prompt typed during a turn goes out at once, the way the CLI delivers it`() {
        queue.enqueue("first", emptyList(), "first")
        queue.enqueue("second", emptyList(), "second")

        assertEquals(2, written.size)
        assertEquals(emptyList<String>(), queue.queued())
        assertEquals(listOf("first", "second"), transcript.entries.map { it.text })
    }

    @Test
    fun `a prompt typed before the session is ready waits, and can be removed before it goes out`() {
        ready = false
        queue.enqueue("first", emptyList(), "first")
        queue.enqueue("second", emptyList(), "second")
        queue.enqueue("third", emptyList(), "third")
        assertEquals(0, written.size)
        queue.remove(1)
        assertEquals(listOf("first", "third"), queue.queued())

        sessionReady()
        assertEquals(listOf("first", "third"), transcript.entries.map { it.text })
        assertEquals(emptyList<String>(), queue.queued())
    }

    @Test
    fun `a prompt the process could not take stays queued and leaves no row behind`() {
        writeSucceeds = false
        queue.enqueue("lost?", emptyList(), "lost?")
        assertEquals(listOf("lost?"), queue.queued())
        assertEquals(emptyList<String>(), transcript.entries.map { it.text })
        assertNull(queue.currentUserMessageId)

        writeSucceeds = true
        queue.pump()
        assertEquals(emptyList<String>(), queue.queued())
        assertEquals(listOf("lost?"), transcript.entries.map { it.text })
    }

    @Test
    fun `sending clears the suggestion and a tool call is bound to the turn that sent it`() {
        queue.suggest("Add tests")
        assertEquals("Add tests", queue.suggestion)
        queue.enqueue("go", emptyList(), "go")
        assertNull(queue.suggestion)
        queue.bindTool("tool-1")
        assertEquals(queue.currentUserMessageId, queue.userMessageIdFor("tool-1"))
        queue.forgetTurn()
        assertNull(queue.userMessageIdFor("tool-1"))
    }
}
