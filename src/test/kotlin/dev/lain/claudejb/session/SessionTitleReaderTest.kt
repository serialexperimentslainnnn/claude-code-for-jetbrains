package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Tests [SessionTitleReader.pickTitle]'s pure title-selection logic over raw JSONL lines (no filesystem):
 * ai-title is used, a `/rename` customTitle wins, the last value of each wins, and corrupt/blank input
 * never throws.
 */
class SessionTitleReaderTest {

    @Test
    fun `picks the ai-title`() {
        val lines = listOf(
            """{"type":"queue-operation","sessionId":"s1"}""",
            """{"type":"ai-title","aiTitle":"Plugin reinstalled","sessionId":"s1"}""",
        )
        assertEquals("Plugin reinstalled", SessionTitleReader.pickTitle(lines))
    }

    @Test
    fun `customTitle wins over ai-title regardless of order`() {
        val lines = listOf(
            """{"type":"ai-title","aiTitle":"Auto generated","sessionId":"s1"}""",
            """{"customTitle":"My rename","sessionId":"s1"}""",
        )
        assertEquals("My rename", SessionTitleReader.pickTitle(lines))
    }

    @Test
    fun `last ai-title wins`() {
        val lines = listOf(
            """{"type":"ai-title","aiTitle":"First","sessionId":"s1"}""",
            """{"type":"ai-title","aiTitle":"Second","sessionId":"s1"}""",
        )
        assertEquals("Second", SessionTitleReader.pickTitle(lines))
    }

    @Test
    fun `skips blank and corrupt lines, never throws`() {
        val lines = listOf(
            "",
            "   ",
            "{not json",
            """{"type":"ai-title","aiTitle":"","sessionId":"s1"}""", // blank ai title ignored
            """{"type":"ai-title","aiTitle":"Good","sessionId":"s1"}""",
        )
        assertEquals("Good", SessionTitleReader.pickTitle(lines))
    }

    @Test
    fun `no title line yields null`() {
        val lines = listOf(
            """{"type":"queue-operation","sessionId":"s1"}""",
            """{"type":"user","content":"hi"}""",
        )
        assertNull(SessionTitleReader.pickTitle(lines))
        assertNull(SessionTitleReader.pickTitle(emptyList()))
    }
}
