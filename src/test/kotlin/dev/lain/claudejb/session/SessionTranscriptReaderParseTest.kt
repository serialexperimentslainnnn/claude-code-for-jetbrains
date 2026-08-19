package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class SessionTranscriptReaderParseTest {

    private fun user(text: String) =
        """{"type":"user","message":{"role":"user","content":"$text"}}"""

    private fun assistantText(text: String) =
        """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"$text"}]}}"""

    @Test
    fun `a string prompt and an array prompt both come back as USER text`() {
        val entries = SessionTranscriptReader.parseEntries(
            listOf(
                user("plain string content"),
                """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"array block"}]}}""",
                """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"   "}]}}""",
            ),
        )
        assertEquals(listOf("plain string content", "array block"), entries.map { it.text })
        assertTrue(entries.all { it.speaker == "USER" })
    }

    @Test
    fun `thinking and text blocks keep their distinct speakers`() {
        val entries = SessionTranscriptReader.parseEntries(
            listOf(
                """{"type":"assistant","message":{"content":[
                   {"type":"thinking","thinking":"weighing it up"},
                   {"type":"text","text":"the answer"}]}}""".replace("\n", ""),
            ),
        )
        assertEquals(listOf("THINKING" to "weighing it up", "ASSISTANT" to "the answer"), entries.map { it.speaker to it.text })
    }

    @Test
    fun `a call with no result is in flight, and one with a failed result is marked failed`() {
        val entries = SessionTranscriptReader.parseEntries(
            listOf(
                """{"type":"assistant","message":{"role":"assistant","content":[
                   {"type":"tool_use","id":"live","name":"Bash","input":{"command":"sleep 60"}}]}}""".replace("\n", ""),
                """{"type":"assistant","message":{"role":"assistant","content":[
                   {"type":"tool_use","id":"boom","name":"Bash","input":{"command":"false"}}]}}""".replace("\n", ""),
                """{"type":"user","message":{"role":"user","content":[
                   {"type":"tool_result","tool_use_id":"boom","is_error":true,"content":"exit 1"}]}}""".replace("\n", ""),
                """{"type":"assistant","message":{"role":"assistant","content":[
                   {"type":"tool_use","id":"ok","name":"Read","input":{"file_path":"/tmp/x"}}]}}""".replace("\n", ""),
                """{"type":"user","message":{"role":"user","content":[
                   {"type":"tool_result","tool_use_id":"ok","content":"contents"}]}}""".replace("\n", ""),
            ),
        )
        val calls = entries.filter { it.speaker == "TOOL" }.associateBy { it.toolUseId }
        assertTrue(calls["live"]!!.inFlight, "a call with no result is still running")
        assertTrue(!calls["live"]!!.failed)
        assertTrue(calls["boom"]!!.failed, "a call whose result is an error has failed")
        assertTrue(!calls["boom"]!!.inFlight)
        assertTrue(!calls["ok"]!!.inFlight && !calls["ok"]!!.failed, "a call that returned is simply done")
    }

    @Test
    fun `a tool_result is attributed to TOOL_OUTPUT and carries its error flag`() {
        val entries = SessionTranscriptReader.parseEntries(
            listOf(
                """{"type":"user","message":{"content":[
                   {"type":"tool_result","tool_use_id":"t1","content":"it worked"}]}}""".replace("\n", ""),
                """{"type":"user","message":{"content":[
                   {"type":"tool_result","tool_use_id":"t2","is_error":true,"content":[
                     {"type":"text","text":"line one"},{"type":"text","text":"line two"}]}]}}""".replace("\n", ""),
            ),
        )
        assertEquals(listOf("TOOL_OUTPUT", "TOOL_OUTPUT"), entries.map { it.speaker })
        assertEquals("it worked", entries[0].text)
        assertNull(entries[0].meta)
        assertEquals("line one\nline two", entries[1].text)
        assertEquals("error", entries[1].meta)
    }

    @Test
    fun `a command's output is tagged command, even though its call is on an earlier line`() {
        val entries = SessionTranscriptReader.parseEntries(
            listOf(
                """{"type":"assistant","message":{"content":[
                   {"type":"tool_use","id":"c1","name":"Bash","input":{"command":"ls -la"}}]}}""".replace("\n", ""),
                """{"type":"user","message":{"content":[
                   {"type":"tool_result","tool_use_id":"c1","content":"total 0"}]}}""".replace("\n", ""),
                """{"type":"assistant","message":{"content":[
                   {"type":"tool_use","id":"r1","name":"Read","input":{"file_path":"/tmp/x"}}]}}""".replace("\n", ""),
                """{"type":"user","message":{"content":[
                   {"type":"tool_result","tool_use_id":"r1","content":"file body"}]}}""".replace("\n", ""),
            ),
        )
        val byId = entries.filter { it.speaker == "TOOL_OUTPUT" }.associateBy { it.toolUseId }
        assertEquals("command", byId["c1"]?.meta)
        assertNull(byId["r1"]?.meta, "a non-command tool's output must not be tagged")
        assertEquals("ls -la", entries.first { it.toolUseId == "c1" && it.speaker == "TOOL" }.commandText)
    }

    @Test
    fun `an error on a command's output keeps both tags`() {
        val entries = SessionTranscriptReader.parseEntries(
            listOf(
                """{"type":"assistant","message":{"content":[
                   {"type":"tool_use","id":"c1","name":"Bash","input":{"command":"false"}}]}}""".replace("\n", ""),
                """{"type":"user","message":{"content":[
                   {"type":"tool_result","tool_use_id":"c1","is_error":true,"content":"boom"}]}}""".replace("\n", ""),
            ),
        )
        assertEquals("command error", entries.first { it.speaker == "TOOL_OUTPUT" }.meta)
    }

    @Test
    fun `the tail cap drops an output whose call fell outside the window`() {
        val lines = listOf(
            """{"type":"assistant","message":{"content":[
               {"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"/tmp/a"}}]}}""".replace("\n", ""),
            """{"type":"user","message":{"content":[
               {"type":"tool_result","tool_use_id":"t1","content":"body"}]}}""".replace("\n", ""),
            user("still here"),
        )
        val capped = SessionTranscriptReader.parseEntries(lines, maxEntries = 2)
        assertEquals(listOf("USER"), capped.map { it.speaker })
        assertEquals(3, SessionTranscriptReader.parseEntries(lines, maxEntries = 0).size)
        assertEquals(3, SessionTranscriptReader.parseEntries(lines, maxEntries = null).size)
    }

    @Test
    fun `corrupt, blank and unknown lines are skipped rather than fatal`() {
        val entries = SessionTranscriptReader.parseEntries(
            listOf(
                "",
                "   ",
                "not json at all",
                """{"type":"ai-title","title":"whatever"}""",
                """{"type":"summary","summary":"skip me"}""",
                user("the only turn"),
            ),
        )
        assertEquals(listOf("the only turn"), entries.map { it.text })
    }

    @Test
    fun `metadata takes the first prompt, branch and timestamp it finds`() {
        val meta = SessionTranscriptReader.parseMetadata(
            listOf(
                "garbage",
                """{"type":"user","gitBranch":"feature/x","timestamp":"2026-08-06T10:00:00Z",
                   "message":{"content":[{"type":"tool_result","tool_use_id":"t","content":"not a prompt"}]}}"""
                    .replace("\n", ""),
                """{"type":"user","gitBranch":"ignored-later","timestamp":"2026-08-06T11:00:00Z",
                   "message":{"content":[{"type":"text","text":"the real first prompt"}]}}""".replace("\n", ""),
            ),
        )
        assertEquals("the real first prompt", meta.firstPrompt)
        assertEquals("feature/x", meta.gitBranch)
        assertEquals("2026-08-06T10:00:00Z", meta.createdAt)
    }

    @Test
    fun `metadata is all-null for a transcript that carries none of it`() {
        val meta = SessionTranscriptReader.parseMetadata(listOf(assistantText("model only"), "{}"))
        assertNull(meta.firstPrompt)
        assertNull(meta.gitBranch)
        assertNull(meta.createdAt)
    }

    @Test
    fun `the store lists a project's transcripts newest-first and tolerates an absent tree`() {
        val home = Files.createTempDirectory("claudejb-list-home")
        val originalHome = System.getProperty("user.home")
        try {
            System.setProperty("user.home", home.toString())
            assertNull(SessionStore.projectDir("/tmp/proj"), "no tree yet → no project dir")
            assertTrue(SessionStore.listFiles("/tmp/proj").isEmpty(), "no tree yet → no files")
            assertTrue(SessionStore.listFiles("").isEmpty(), "a blank base path resolves nothing")

            val dir = home.resolve(".claude").resolve("projects").resolve(SessionStore.encodePath("/tmp/proj"))
            Files.createDirectories(dir)
            val older = dir.resolve("11111111-1111-1111-1111-111111111111.jsonl")
            val newer = dir.resolve("22222222-2222-2222-2222-222222222222.jsonl")
            Files.writeString(older, "{}")
            Files.writeString(newer, "{}")
            Files.setLastModifiedTime(older, java.nio.file.attribute.FileTime.fromMillis(1_000_000))
            Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(2_000_000))
            Files.writeString(dir.resolve("notes.txt"), "ignore me")

            assertEquals(dir, SessionStore.projectDir("/tmp/proj"))
            assertEquals(listOf(newer, older), SessionStore.listFiles("/tmp/proj"))
        } finally {
            System.setProperty("user.home", originalHome)
            Files.walk(home).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
