package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        val trims = mutableListOf<Pair<List<Long>, Int>>()
        var cleared = 0
        override fun onAdded(entry: TranscriptEntry, index: Int) {
            added += entry to index
        }
        override fun onCleared() {
            cleared++
        }
        override fun onTrimmed(removedIds: List<Long>, totalTrimmed: Int) {
            trims += removedIds to totalTrimmed
        }
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
    fun `parallel tool outputs each anchor after their own call, not at the tail`() {
        // Two interleaved tool calls; outputs arrive out of order. Each output must slot in right after its
        // own call (and any earlier outputs of that call), proving the byToolUseId+indexOf path locates the
        // right TOOL entry as insert positions shift — never scattering results at the transcript tail.
        val model = TranscriptModel()
        val t1 = model.add(Speaker.TOOL, "Read", toolUseId = "t1")
        val t2 = model.add(Speaker.TOOL, "Bash", toolUseId = "t2")
        val tail = model.add(Speaker.ASSISTANT, "done")

        val o2 = model.addToolOutput("t2", "bash out")
        val o1 = model.addToolOutput("t1", "read out")
        val o1b = model.addToolOutput("t1", "read out 2")

        // t1 keeps its outputs stacked after t1; t2's output stays after t2; the tail entry stays last.
        assertEquals(listOf(t1, o1, o1b, t2, o2, tail), model.entries)
    }

    @Test
    fun `nested subagent subtree stays contiguous in order under its ancestor`() {
        // t1 (Agent) → t2 (sub-Agent under t1) → t3 (tool under t2). A top-level tail is added between the
        // nestings; every descendant must remain inside t1's block, in hierarchy order, before the tail —
        // exercising belongsToSubtree across two levels while insertion indices shift.
        val model = TranscriptModel()
        val t1 = model.add(Speaker.TOOL, "Task", toolUseId = "t1")
        val tail = model.add(Speaker.ASSISTANT, "top-level after agent")
        val t2 = model.add(Speaker.TOOL, "Task", toolUseId = "t2", parentToolUseId = "t1")
        val t3 = model.add(Speaker.TOOL, "Grep", toolUseId = "t3", parentToolUseId = "t2")
        val o3 = model.addToolOutput("t3", "grep out")

        // t1, then its whole subtree (t2, t3, t3's output) contiguous, then the top-level tail.
        assertEquals(listOf(t1, t2, t3, o3, tail), model.entries)
        assertEquals("t1", model.parentToolOf("t2"))
        assertEquals("t2", model.parentToolOf("t3"))
    }

    @Test
    fun `duplicate tool_use_id anchors output under the latest call without crashing`() {
        // On resume/fork replay the binary can re-emit a tool_use_id. byToolUseId then holds the LAST TOOL entry
        // for that id; both calls remain in backing, so indexOf finds the current (second) call — no -1, no crash.
        // The output must anchor right after the second call, never scatter to the tail or throw.
        val model = TranscriptModel()
        val first = model.add(Speaker.TOOL, "Read", toolUseId = "t1")
        val between = model.add(Speaker.ASSISTANT, "between")
        val second = model.add(Speaker.TOOL, "Read", toolUseId = "t1")

        val out = model.addToolOutput("t1", "file contents")

        // Output slots after the second (current) call; both calls and the in-between entry stay intact.
        assertEquals(listOf(first, between, second, out), model.entries)
    }

    @Test
    fun `duplicate parent tool_use_id nests child under the latest parent without crashing`() {
        // Same replay scenario for insertionIndexFor: a re-emitted parent id maps to the latest parent TOOL row;
        // a child nesting under it must land inside the second parent's block, never throw IndexOutOfBounds.
        val model = TranscriptModel()
        val first = model.add(Speaker.TOOL, "Task", toolUseId = "t1")
        val tail = model.add(Speaker.ASSISTANT, "after first agent")
        val second = model.add(Speaker.TOOL, "Task", toolUseId = "t1")
        val child = model.add(Speaker.TOOL, "Grep", toolUseId = "t2", parentToolUseId = "t1")

        // Child nests right after the second (current) parent; the tail stays before it as a top-level entry.
        assertEquals(listOf(first, tail, second, child), model.entries)
        assertEquals("t1", model.parentToolOf("t2"))
    }

    @Test
    fun `a re-emitted tool id that is no longer nested stops reporting the old parent`() {
        // The parent mapping describes whichever row the id currently resolves to, and a replayed call can come
        // back with a different shape: nested under an Agent the first time, top-level the second. Left behind,
        // the old parent keeps nesting the id — parentToolOf answers with an Agent the live row does not belong
        // to, and every later row that consults it is placed inside that stale subtree.
        val model = TranscriptModel()
        model.add(Speaker.TOOL, "Task(inventory)", meta = "Task", toolUseId = "agent")
        model.add(Speaker.TOOL, "Read(a.kt)", meta = "Read", toolUseId = "t1", parentToolUseId = "agent")

        model.add(Speaker.TOOL, "Bash(ls)", meta = "Bash", toolUseId = "t1")

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

    private val cap = TranscriptModel.MAX_ENTRIES

    /** Fills the model with [count] plain rows, so a test only writes the entries it actually asserts on. */
    private fun TranscriptModel.fill(count: Int) = repeat(count) { add(Speaker.USER, "x") }

    @Test
    fun `nothing is trimmed at or below the cap`() {
        val model = TranscriptModel()
        val listener = RecordingListener()
        model.addListener(listener)

        model.fill(cap)

        assertEquals(cap, model.entries.size)
        assertEquals(0, model.trimmedCount)
        // No empty notification either: a pass that removes nothing fires nothing.
        assertTrue(listener.trims.isEmpty())
        assertTrue(model.entries.none { it.trimmed })
    }

    @Test
    fun `crossing the cap keeps exactly the cap and drops the oldest rows`() {
        val model = TranscriptModel()
        model.fill(cap + 3)

        assertEquals(cap, model.entries.size)
        // Ids 0..2 were the oldest and went; the surviving head is id 3 and the tail is the newest row.
        assertEquals(3L, model.entries.first().id)
        assertEquals((cap + 2).toLong(), model.entries.last().id)
        assertEquals(3, model.trimmedCount)
    }

    @Test
    fun `a dropped entry is marked trimmed and a surviving one is not`() {
        val model = TranscriptModel()
        val oldest = model.add(Speaker.USER, "first")
        model.fill(cap)

        assertTrue(oldest.trimmed)
        assertFalse(model.entries.first().trimmed)
    }

    @Test
    fun `onTrimmed fires once per pass with the removed ids and the cumulative total`() {
        val model = TranscriptModel()
        val listener = RecordingListener()
        model.addListener(listener)

        model.fill(cap) // exactly at the cap: still nothing to drop
        model.add(Speaker.ASSISTANT, "over by one")
        model.add(Speaker.ASSISTANT, "over by two")

        assertEquals(listOf(listOf(0L) to 1, listOf(1L) to 2), listener.trims)
        assertEquals(2, model.trimmedCount)
    }

    @Test
    fun `trimmedCount accumulates across passes and is not reset by them`() {
        val model = TranscriptModel()
        model.fill(cap + 5)
        assertEquals(5, model.trimmedCount)
        model.fill(4)
        assertEquals(9, model.trimmedCount)
        assertEquals(cap, model.entries.size)
    }

    @Test
    fun `a trimmed tool call stops resolving and every lookup degrades quietly`() {
        val model = TranscriptModel()
        // `meta` is the tool NAME and `text` the rendered label, as ClaudeSession builds them: an assertion on
        // toolNameOf is only worth making against a row that carries a name to lose.
        val tool = model.add(Speaker.TOOL, "Bash(ls -la)", meta = "Bash", toolUseId = "t1", commandText = "ls -la")
        val child = model.add(Speaker.TOOL, "Grep(TODO)", meta = "Grep", toolUseId = "t2", parentToolUseId = "t1")
        model.fill(cap)

        assertTrue(tool.trimmed)
        assertTrue(child.trimmed)
        // The per-session maps were pruned with the rows, so nothing resolves and nothing throws.
        assertNull(model.toolNameOf("t1"))
        assertNull(model.commandTextOf("t1"))
        assertFalse(model.isCommandCall("t1"))
        assertNull(model.parentToolOf("t2"))
        model.setToolState("t1", ToolState.ERROR)
        assertFalse(model.setToolTitle("t1", "anything"))
    }

    @Test
    fun `a re-emitted tool id still resolves to its live row after the older one was trimmed`() {
        val model = TranscriptModel()
        val old = model.add(Speaker.TOOL, "Read(a.kt)", meta = "Read", toolUseId = "t1")
        model.fill(cap - 1)
        val newer = model.add(Speaker.TOOL, "Bash(ls)", meta = "Bash", toolUseId = "t1", commandText = "ls")

        // The head row went, but the map points at the newer call, so the mapping must survive the prune. The
        // two calls carry different tool names on purpose: resolving to the trimmed row fails as loudly as
        // losing the mapping altogether, which is what makes this assertion worth making.
        assertTrue(old.trimmed)
        assertFalse(newer.trimmed)
        assertEquals("Bash", model.toolNameOf("t1"))
        assertEquals("ls", model.commandTextOf("t1"))
        assertTrue(model.setToolTitle("t1", "listing"))
    }

    @Test
    fun `addToolOutput for a trimmed call appends at the tail`() {
        val model = TranscriptModel()
        val tool = model.add(Speaker.TOOL, "Read", toolUseId = "t1")
        model.fill(cap)
        assertTrue(tool.trimmed)

        val out = model.addToolOutput("t1", "file contents")

        assertSame(out, model.entries.last())
        assertEquals(cap, model.entries.size)
    }

    @Test
    fun `clear zeroes trimmedCount`() {
        val model = TranscriptModel()
        model.fill(cap + 2)
        assertEquals(2, model.trimmedCount)

        model.clear()

        assertEquals(0, model.trimmedCount)
        assertTrue(model.entries.isEmpty())
    }
}
