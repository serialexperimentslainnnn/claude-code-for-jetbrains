package dev.lain.claudejb.integration

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.session.SessionTranscriptReader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resume path: the `claude` binary's own JSONL sidecar under `~/.claude/projects/<encoded>/<id>.jsonl` is the
 * source of truth for past conversations. [SessionTranscriptReader.readEntries] reconstructs the transcript
 * from it. This test redirects `user.home` to a temp tree (so [SessionStore] reads our fixture file, not the
 * real home) and verifies the round-trip: user prompt, assistant text, a tool_use TOOL row, and its
 * tool_result TOOL_OUTPUT.
 *
 * No process is started — this is pure JSONL reconstruction (the same code [ClaudeSession.restore] feeds on).
 * The pure parser is also covered by SessionTranscriptReaderTest; here we exercise the IO-backed entry point.
 */
class ResumeFlowIntegrationTest : BasePlatformTestCase() {

    private var savedHome: String? = null
    private var tmpHome: Path? = null

    override fun tearDown() {
        try {
            savedHome?.let { System.setProperty("user.home", it) }
            tmpHome?.let { runCatching { it.toFile().deleteRecursively() } }
        } finally {
            super.tearDown()
        }
    }

    fun `test readEntries reconstructs a past session from the binary sidecar`() {
        val sessionId = "abcdabcd-1234-5678-9abc-def012345678"
        val home = Files.createTempDirectory("fake-home")
        tmpHome = home
        savedHome = System.getProperty("user.home")
        System.setProperty("user.home", home.toString())

        // The folder name is the binary's cwd-encoding; locate() finds the file by UUID regardless, so any
        // project subfolder works.
        val projectDir = home.resolve(".claude").resolve("projects").resolve("-tmp-project")
        Files.createDirectories(projectDir)
        val jsonl = listOf(
            """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"Refactor the parser"}]}}""",
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Sure, here is the plan."}]}}""",
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_x","name":"Read","input":{"file_path":"src/Parser.kt"}}]}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_x","content":"package …"}]}}""",
        )
        Files.write(projectDir.resolve("$sessionId.jsonl"), jsonl)

        val entries = SessionTranscriptReader.readEntries(sessionId)

        assertTrue("USER prompt", entries.any { it.speaker == "USER" && it.text.contains("Refactor the parser") })
        assertTrue("ASSISTANT text", entries.any { it.speaker == "ASSISTANT" && it.text.contains("here is the plan") })
        val tool = entries.singleOrNull { it.speaker == "TOOL" }
        assertNotNull("TOOL row", tool)
        assertEquals("Read", tool!!.meta)
        assertEquals("toolu_x", tool.toolUseId)
        assertTrue("TOOL text formatted", tool.text.contains("Parser.kt"))
        assertTrue(
            "TOOL_OUTPUT anchored",
            entries.any { it.speaker == "TOOL_OUTPUT" && it.toolUseId == "toolu_x" && it.text.contains("package") },
        )
    }
}
