package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
        assertNull(e.filePath) // not a file tool → nothing to jump to
    }

    /**
     * Regression: a RESTORED conversation must render its tool cards exactly like a live one — project-relative
     * path, and a jump-to-code link. The restore path used to call `formatToolUse` with no project root, so
     * reopening the IDE turned every card into a bare absolute path with no link.
     */
    @Test
    fun `a restored file tool keeps the project-relative path and its jump-to-code link`() {
        val root = "/home/u/proj"
        val lines = listOf(
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_2",""" +
                """"name":"Read","input":{"file_path":"$root/src/main/Foo.kt"}}]}}"""
        )
        val e = SessionTranscriptReader.parseEntries(lines, projectRoot = root).single()
        assertEquals("Read(src/main/Foo.kt)", e.text)
        assertEquals("src/main/Foo.kt", e.filePath)
    }

    @Test
    fun `without a project root a restored file tool falls back to the raw path and no link`() {
        val lines = listOf(
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_3",""" +
                """"name":"Read","input":{"file_path":"/home/u/proj/src/main/Foo.kt"}}]}}"""
        )
        val e = SessionTranscriptReader.parseEntries(lines).single()
        assertEquals("Read(/home/u/proj/src/main/Foo.kt)", e.text)
        assertEquals("/home/u/proj/src/main/Foo.kt", e.filePath)
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

    @Test
    fun `parseMetadata extracts first prompt, git branch and earliest timestamp`() {
        val lines = listOf(
            """{"type":"queue-operation","sessionId":"s1","timestamp":"2026-05-27T21:29:10.205Z"}""",
            """{"type":"user","gitBranch":"feature/x","timestamp":"2026-05-27T21:29:11.000Z","message":{"role":"user","content":"do the thing"}}""",
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"sure"}]}}""",
            """{"type":"user","gitBranch":"feature/y","message":{"role":"user","content":"and another"}}""",
        )
        val meta = SessionTranscriptReader.parseMetadata(lines)
        assertEquals("do the thing", meta.firstPrompt)
        assertEquals("feature/x", meta.gitBranch)
        assertEquals("2026-05-27T21:29:10.205Z", meta.createdAt)
    }

    @Test
    fun `parseMetadata reads first text block of an array prompt and ignores tool_result`() {
        val lines = listOf(
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"x"}]}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"real prompt"}]}}""",
        )
        val meta = SessionTranscriptReader.parseMetadata(lines)
        assertEquals("real prompt", meta.firstPrompt)
    }

    @Test
    fun `parseMetadata returns nulls when nothing is present and never throws on junk`() {
        val meta = SessionTranscriptReader.parseMetadata(listOf("", "  ", "{not json", """{"type":"summary"}"""))
        assertNull(meta.firstPrompt)
        assertNull(meta.gitBranch)
        assertNull(meta.createdAt)
    }

    @Test
    fun `maxEntries keeps only the last N entries in order`() {
        val lines = (1..10).map {
            """{"type":"user","message":{"role":"user","content":"msg $it"}}"""
        }
        val entries = SessionTranscriptReader.parseEntries(lines, maxEntries = 3)
        assertEquals(3, entries.size)
        assertEquals(listOf("msg 8", "msg 9", "msg 10"), entries.map { it.text })
    }

    @Test
    fun `maxEntries null returns the full transcript unchanged`() {
        val lines = (1..5).map { """{"type":"user","message":{"role":"user","content":"m$it"}}""" }
        val full = SessionTranscriptReader.parseEntries(lines)
        val explicitNull = SessionTranscriptReader.parseEntries(lines, maxEntries = null)
        assertEquals(5, full.size)
        assertEquals(full.map { it.text }, explicitNull.map { it.text })
    }

    @Test
    fun `maxEntries larger than size returns everything without crashing`() {
        val lines = (1..3).map { """{"type":"user","message":{"role":"user","content":"m$it"}}""" }
        val entries = SessionTranscriptReader.parseEntries(lines, maxEntries = 100)
        assertEquals(3, entries.size)
        assertEquals(listOf("m1", "m2", "m3"), entries.map { it.text })
    }

    @Test
    fun `maxEntries of zero or negative is treated as no cap`() {
        val lines = (1..4).map { """{"type":"user","message":{"role":"user","content":"m$it"}}""" }
        assertEquals(4, SessionTranscriptReader.parseEntries(lines, maxEntries = 0).size)
        assertEquals(4, SessionTranscriptReader.parseEntries(lines, maxEntries = -5).size)
    }

    @Test
    fun `maxEntries drops a leading orphan TOOL_OUTPUT whose call was cut off`() {
        // Sequence: TOOL(t1), TOOL_OUTPUT(t1), USER, ASSISTANT. Cap of 3 cuts the TOOL call, leaving an orphan
        // output at the head of the window — it must be dropped so no result shows without its call.
        val lines = listOf(
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"out"}]}}""",
            """{"type":"user","message":{"role":"user","content":"next prompt"}}""",
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"reply"}]}}""",
        )
        val entries = SessionTranscriptReader.parseEntries(lines, maxEntries = 3)
        // Window would be [TOOL_OUTPUT(t1), USER, ASSISTANT]; the orphan output is dropped.
        assertEquals(listOf("USER", "ASSISTANT"), entries.map { it.speaker })
        assertEquals(listOf("next prompt", "reply"), entries.map { it.text })
    }

    @Test
    fun `maxEntries keeps a TOOL_OUTPUT when its TOOL call is inside the window`() {
        val lines = listOf(
            """{"type":"user","message":{"role":"user","content":"prompt"}}""",
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"out"}]}}""",
        )
        val entries = SessionTranscriptReader.parseEntries(lines, maxEntries = 2)
        assertEquals(listOf("TOOL", "TOOL_OUTPUT"), entries.map { it.speaker })
    }

    @Test
    fun `maxEntries drops an orphan TOOL_OUTPUT in the middle while keeping a coherent pair`() {
        // Sequence: TOOL(t0), USER, TOOL_OUTPUT(t0 - call cut off), ASSISTANT, TOOL(t1), TOOL_OUTPUT(t1).
        // Cap of 5 cuts the t0 TOOL call but leaves its output mid-window (after a surviving non-orphan entry);
        // it must still be dropped, while the coherent t1 TOOL+TOOL_OUTPUT pair is kept, order preserved.
        val lines = listOf(
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"t0","name":"Bash","input":{"command":"a"}}]}}""",
            """{"type":"user","message":{"role":"user","content":"prompt"}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t0","content":"orphan out"}]}}""",
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"reply"}]}}""",
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"b"}}]}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"good out"}]}}""",
        )
        val entries = SessionTranscriptReader.parseEntries(lines, maxEntries = 5)
        // Window would be [USER, TOOL_OUTPUT(t0), ASSISTANT, TOOL(t1), TOOL_OUTPUT(t1)]; the mid orphan is dropped.
        assertEquals(listOf("USER", "ASSISTANT", "TOOL", "TOOL_OUTPUT"), entries.map { it.speaker })
        assertEquals(listOf("prompt", "reply", "good out"), entries.filter { it.speaker != "TOOL" }.map { it.text })
        assertEquals("t1", entries.last().toolUseId)
    }

    @Test
    fun `DEFAULT_RESTORE_CAP is a positive conservative cap`() {
        assertTrue(SessionTranscriptReader.DEFAULT_RESTORE_CAP > 0)
    }
}
