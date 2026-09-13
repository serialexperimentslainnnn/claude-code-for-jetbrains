package dev.lain.claudejb.model.session.turn

import dev.lain.claudejb.model.protocol.ClaudeEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StreamBufferTest {

    private val buffer = StreamBuffer()

    @Test
    fun `consecutive deltas of one kind coalesce into one run`() {
        buffer.buffer(ClaudeEvent.TextDelta("Hel", null))
        buffer.buffer(ClaudeEvent.TextDelta("lo", null))
        buffer.buffer(ClaudeEvent.ThinkingDelta("hm", null))
        buffer.buffer(ClaudeEvent.TextDelta("!", null))
        val drained = buffer.drain()!!
        assertEquals(listOf(false to "Hello", true to "hm", false to "!"), drained.runs)
    }

    @Test
    fun `draining empties the buffer and an empty buffer drains to null`() {
        assertNull(buffer.drain())
        buffer.buffer(ClaudeEvent.TextDelta("x", null))
        buffer.drain()
        assertNull(buffer.drain())
    }

    @Test
    fun `a subagent's deltas never reach the main transcript`() {
        buffer.buffer(ClaudeEvent.TextDelta("theirs", "tool-1"))
        buffer.buffer(ClaudeEvent.ThinkingDelta("theirs too", "tool-1"))
        assertNull(buffer.drain())
    }

    @Test
    fun `the last usage wins and travels with the runs`() {
        buffer.buffer(ClaudeEvent.LiveUsage(1, 2, 3, 4))
        buffer.buffer(ClaudeEvent.LiveUsage(5, 6, 7, 8))
        val drained = buffer.drain()!!
        assertEquals(listOf(5, 6, 7, 8), drained.usage!!.toList())
        assertEquals(emptyList<Pair<Boolean, String>>(), drained.runs)
    }
}
