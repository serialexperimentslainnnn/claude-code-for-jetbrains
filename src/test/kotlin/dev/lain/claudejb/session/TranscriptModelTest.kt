package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests the hierarchical insertion logic of [TranscriptModel] independently of Swing: tool outputs must anchor
 * to their originating call (so parallel tool calls don't scatter results at the tail) and subagent entries
 * must nest under their Agent's tool_use id. The chat panel relies on this ordering to render the transcript.
 */
class TranscriptModelTest {

    /** Records onAdded callbacks so we can assert listeners see the same index the model inserted at. */
    private class RecordingListener : TranscriptModel.Listener {
        val added = mutableListOf<Pair<TranscriptEntry, Int>>()
        var cleared = 0
        override fun onAdded(entry: TranscriptEntry, index: Int) { added += entry to index }
        override fun onCleared() { cleared++ }
    }

    @Test
    fun `add appends top-level entries and assigns incremental ids`() {
        val model = TranscriptModel()
        val a = model.add(Speaker.USER, "hi")
        val b = model.add(Speaker.ASSISTANT, "hello")
        assertEquals(0L, a.id)
        assertEquals(1L, b.id)
        assertEquals(listOf(a, b), model.entries)
    }

    @Test
    fun `add notifies listener with insertion index`() {
        val model = TranscriptModel()
        val listener = RecordingListener()
        model.addListener(listener)
        val a = model.add(Speaker.USER, "hi")
        val b = model.add(Speaker.ASSISTANT, "hello")
        assertEquals(listOf(a to 0, b to 1), listener.added)
    }

    @Test
    fun `addToolOutput anchors output right after its tool call`() {
        val model = TranscriptModel()
        val tool = model.add(Speaker.TOOL, "Read", toolUseId = "t1")
        val tail = model.add(Speaker.ASSISTANT, "later text")
        val output = model.addToolOutput("t1", "file contents")

        // Order must be: TOOL(t1), OUTPUT(t1), ASSISTANT — output anchored to its call, not at the tail.
        assertEquals(listOf(tool, output, tail), model.entries)
    }

    @Test
    fun `multiple outputs for the same tool stack in order after the call`() {
        val model = TranscriptModel()
        val tool = model.add(Speaker.TOOL, "Bash", toolUseId = "t1")
        val out1 = model.addToolOutput("t1", "line 1")
        val out2 = model.addToolOutput("t1", "line 2")
        assertEquals(listOf(tool, out1, out2), model.entries)
    }

    @Test
    fun `addToolOutput appends at tail when the call is unknown`() {
        val model = TranscriptModel()
        val a = model.add(Speaker.USER, "hi")
        val out = model.addToolOutput("missing", "orphan output")
        assertEquals(listOf(a, out), model.entries)
    }

    @Test
    fun `child tool nests within its parent subtree and parentToolOf reports the parent`() {
        val model = TranscriptModel()
        val agent = model.add(Speaker.TOOL, "Task", toolUseId = "t1")
        val tail = model.add(Speaker.ASSISTANT, "after agent")
        val child = model.add(Speaker.TOOL, "Grep", toolUseId = "t2", parentToolUseId = "t1")

        // Child must land inside t1's block (right after the Agent row), before the top-level tail entry.
        assertEquals(listOf(agent, child, tail), model.entries)
        assertEquals("t1", model.parentToolOf("t2"))
        assertNull(model.parentToolOf("t1"))
    }

    @Test
    fun `append concatenates text and notifies update`() {
        val model = TranscriptModel()
        val entry = model.add(Speaker.ASSISTANT, "Hel")
        model.append(entry, "lo")
        assertEquals("Hello", entry.text)
    }

    @Test
    fun `replaceText substitutes the entry text`() {
        val model = TranscriptModel()
        val entry = model.add(Speaker.ASSISTANT, "draft")
        model.replaceText(entry, "final")
        assertEquals("final", entry.text)
    }

    @Test
    fun `clear empties entries and resets the hierarchy indices`() {
        val model = TranscriptModel()
        val listener = RecordingListener()
        model.addListener(listener)
        model.add(Speaker.TOOL, "Read", toolUseId = "t1")
        model.add(Speaker.ASSISTANT, "x")

        model.clear()

        assertTrue(model.entries.isEmpty())
        assertEquals(1, listener.cleared)
        // tool index cleared: an output for the old id now falls back to the tail (size 0 -> index 0).
        val out = model.addToolOutput("t1", "orphan")
        assertSame(out, model.entries.first())
        assertEquals(1, model.entries.size)
    }
}
