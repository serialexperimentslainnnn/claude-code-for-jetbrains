package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [SubagentNotice] — the one line a finished subagent gets in the MAIN transcript.
 *
 * The user-reported failure pinned here: `task_notification.summary` is not a summary. For a subagent it
 * carries the agent's ENTIRE final answer — headings, tables, code blocks, thousands of characters — and the
 * transcript printed it verbatim, dumping a whole report into the middle of the conversation. On a session
 * running a dozen agents that is the same unreadability the per-agent tabs were built to end.
 *
 * The report is not lost and must not be duplicated here: it is the last thing in that agent's own transcript,
 * which its tab reads from the binary's file. This row is a POINTER.
 */
class SubagentNoticeTest {

    @Test
    fun `a whole report is reduced to its first meaningful line`() {
        // Shaped like a real agent report: a markdown heading, then prose, then a table.
        val report = """
            ## Split of protocol/Protocol.kt

            Done. Twelve files, largest 338 lines.

            | File | Lines |
            |---|---|
            | ProtocolJson.kt | 33 |
        """.trimIndent()
        val head = SubagentNotice.headline(report)!!
        assertEquals("Split of protocol/Protocol.kt", head)
        // The ornament goes with it — a row reading "## Split of…" is the markdown leaking through.
        assertTrue(!head.startsWith("#"), "the heading marker must be stripped")
    }

    @Test
    fun `leading blank lines and list markers are skipped, not rendered`() {
        assertEquals("first real content", SubagentNotice.headline("\n\n   \n- first real content\nrest"))
        assertEquals("quoted line", SubagentNotice.headline("> quoted line"))
        assertEquals("bold-ish", SubagentNotice.headline("**bold-ish**"))
    }

    @Test
    fun `nothing to say yields null, so the row says only what the status was`() {
        assertNull(SubagentNotice.headline(""))
        assertNull(SubagentNotice.headline("   \n\t\n  "))
        // Ornament with no content behind it is still nothing to say.
        assertNull(SubagentNotice.headline("###"))
    }

    @Test
    fun `a long line is cut on a word boundary, never mid-word`() {
        val line = "The agent finished the refactor and then verified every single call site by hand " +
            "across the whole repository before reporting back to its supervisor"
        val head = SubagentNotice.headline(line)!!
        assertTrue(head.length <= 121, "capped, got ${head.length}")
        assertTrue(head.endsWith("…"), "a cut line must show it was cut: $head")
        // The cut lands on a space, so the last word is whole.
        assertTrue(line.startsWith(head.removeSuffix("…")), "the head must be a prefix of the original")
        assertTrue(!head.removeSuffix("…").endsWith(" "), "no trailing space before the ellipsis")
    }

    @Test
    fun `a long unbroken token is cut hard rather than left to blow the width`() {
        // A path or a URL has no space to cut on. Falling back to the raw cap is deliberate: the alternative
        // is emitting it uncut, which defeats the cap entirely.
        val head = SubagentNotice.headline("/very/long/" + "segment/".repeat(40))!!
        assertTrue(head.length <= 121, "an unbroken token must still be capped, got ${head.length}")
        assertTrue(head.endsWith("…"))
    }

    @Test
    fun `a line that just fits is left exactly alone`() {
        val exact = "x".repeat(120)
        assertEquals(exact, SubagentNotice.headline(exact), "no ellipsis on a line that fits")
    }
}
