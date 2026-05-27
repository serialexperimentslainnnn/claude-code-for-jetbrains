package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests [SessionTranscriptReader.parseEntries]'s pure JSONL→transcript mapping (no filesystem): user/assistant
 * text, thinking (empty omitted), tool_use, tool_result (string and array content), and that blank/corrupt
 * lines and unknown types are dropped without throwing.
 */
class SessionTranscriptReaderTest {

    @Test
    fun `user string content becomes USER entry`() {
        val lines = listOf("""{"type":"user","message":{"role":"user","content":"hello there"}}""")
        val e = SessionTranscriptReader.parseEntries(lines).single()
        assertEquals("USER", e.speaker)
        assertEquals("hello there", e.text)
    }

    @Test
    fun `assistant text becomes ASSISTANT entry`() {
        val lines = listOf(
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Hi, how can I help?"}]}}"""
        )
        val e = SessionTranscriptReader.parseEntries(lines).single()
        assertEquals("ASSISTANT", e.speaker)
        assertEquals("Hi, how can I help?", e.text)
    }

    @Test
    fun `non-blank thinking becomes THINKING, blank thinking is omitted`() {
        val lines = listOf(
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"thinking","thinking":"Let me reason"}]}}""",
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"thinking","thinking":"   "}]}}""",
        )
        val entries = SessionTranscriptReader.parseEntries(lines)
        assertEquals(1, entries.size)
        assertEquals("THINKING", entries[0].speaker)
        assertEquals("Let me reason", entries[0].text)
    }

    @Test
    fun `tool_use becomes TOOL with meta name and toolUseId`() {
        val lines = listOf(
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"ls -la"}}]}}"""
        )
        val e = SessionTranscriptReader.parseEntries(lines).single()
        assertEquals("TOOL", e.speaker)
        assertEquals("Bash", e.meta)
        assertEquals("toolu_1", e.toolUseId)
        assertTrue(e.text.isNotBlank())
    }

    @Test
    fun `user tool_result with string content becomes TOOL_OUTPUT`() {
        val lines = listOf(
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"total 0"}]}}"""
        )
        val e = SessionTranscriptReader.parseEntries(lines).single()
        assertEquals("TOOL_OUTPUT", e.speaker)
        assertEquals("total 0", e.text)
        assertEquals("toolu_1", e.toolUseId)
    }

    @Test
    fun `user tool_result with array content is concatenated`() {
        val lines = listOf(
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_2","content":[{"type":"text","text":"line one"},{"type":"text","text":"line two"}]}]}}"""
        )
        val e = SessionTranscriptReader.parseEntries(lines).single()
        assertEquals("TOOL_OUTPUT", e.speaker)
        assertEquals("line one\nline two", e.text)
        assertEquals("toolu_2", e.toolUseId)
    }

    @Test
    fun `user text block in array becomes USER`() {
        val lines = listOf(
            """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"a question"}]}}"""
        )
        val e = SessionTranscriptReader.parseEntries(lines).single()
        assertEquals("USER", e.speaker)
        assertEquals("a question", e.text)
    }

    @Test
    fun `tool_use without a name is dropped`() {
        val lines = listOf(
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_x","input":{"command":"ls"}}]}}"""
        )
        assertTrue(SessionTranscriptReader.parseEntries(lines).isEmpty())
    }

    @Test
    fun `tool_result with no extractable content is dropped`() {
        val lines = listOf(
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":[]}]}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_2"}]}}""",
        )
        assertTrue(SessionTranscriptReader.parseEntries(lines).isEmpty())
    }

    @Test
    fun `mixed assistant blocks are emitted in order`() {
        val lines = listOf(
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"thinking","thinking":"plan"},{"type":"text","text":"Doing it"},{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"/a/b/foo.kt"}}]}}"""
        )
        val entries = SessionTranscriptReader.parseEntries(lines)
        assertEquals(listOf("THINKING", "ASSISTANT", "TOOL"), entries.map { it.speaker })
        assertEquals("Read", entries[2].meta)
    }

    @Test
    fun `blank, corrupt and unknown-type lines are dropped without throwing`() {
        val lines = listOf(
            "",
            "   ",
            "{not json",
            """{"type":"ai-title","aiTitle":"Some title"}""",
            """{"type":"queue-operation","sessionId":"s1"}""",
            """{"type":"summary","summary":"x"}""",
        )
        assertTrue(SessionTranscriptReader.parseEntries(lines).isEmpty())
    }
}
