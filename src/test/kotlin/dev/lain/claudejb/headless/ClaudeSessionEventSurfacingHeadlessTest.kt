package dev.lain.claudejb.headless

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.controller.session.ClaudeSession
import dev.lain.claudejb.model.protocol.ClaudeEvent
import dev.lain.claudejb.model.protocol.models.ElicitationRequest
import dev.lain.claudejb.model.protocol.models.FilesPersistedInfo
import dev.lain.claudejb.model.protocol.models.MemoryRecallInfo
import dev.lain.claudejb.model.protocol.models.PersistedFile
import dev.lain.claudejb.model.protocol.models.PromptSuggestionInfo
import dev.lain.claudejb.model.protocol.models.RecalledMemory
import dev.lain.claudejb.model.protocol.models.ThinkingTokensInfo
import dev.lain.claudejb.model.session.transcript.Speaker

class ClaudeSessionEventSurfacingHeadlessTest : BasePlatformTestCase() {

    private fun flush() = PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

    fun `test prompt suggestion is captured and cleared`() {
        val session = ClaudeSession(project, "t")
        try {
            session.handleEventForTest(ClaudeEvent.PromptSuggestion(PromptSuggestionInfo(suggestion = "Add tests")))
            flush()
            assertEquals("Add tests", session.prompts.suggestion)
            session.prompts.clearSuggestion()
            flush()
            assertNull(session.prompts.suggestion)
        } finally {
            session.dispose()
        }
    }

    fun `test thinking tokens update live estimate and reset on message boundary`() {
        val session = ClaudeSession(project, "t")
        try {
            session.handleEventForTest(ClaudeEvent.ThinkingTokens(ThinkingTokensInfo(estimatedTokens = 500)))
            flush()
            assertEquals(500, session.turn.liveThinkingTokens)
            session.handleEventForTest(ClaudeEvent.MessageStart)
            flush()
            assertEquals(0, session.turn.liveThinkingTokens)
        } finally {
            session.dispose()
        }
    }

    fun `test memory recall adds a MEMORY row`() {
        val session = ClaudeSession(project, "t")
        try {
            session.handleEventForTest(
                ClaudeEvent.MemoryRecall(
                    MemoryRecallInfo(mode = "select", memories = listOf(RecalledMemory(path = "a.md", scope = "team"))),
                ),
            )
            flush()
            assertTrue(session.transcript.entries.any { it.speaker == Speaker.MEMORY })
        } finally {
            session.dispose()
        }
    }

    fun `test files persisted success surfaces a notice`() {
        val session = ClaudeSession(project, "t")
        try {
            session.handleEventForTest(
                ClaudeEvent.FilesPersisted(FilesPersistedInfo(files = listOf(PersistedFile(filename = "out.txt")))),
            )
            flush()
            assertTrue(session.transcript.entries.any { it.text.contains("Uploaded") })
        } finally {
            session.dispose()
        }
    }

    fun `test elicitation surfaces a non-modal card`() {
        val session = ClaudeSession(project, "t")
        try {
            session.handleEventForTest(
                ClaudeEvent.Elicitation("r1", ElicitationRequest(mcpServerName = "github", message = "Authorize?")),
            )
            flush()
            val pending = session.cards.pending().single()
            assertEquals("r1", pending.requestId)
            assertNotNull(pending.elicitation)
        } finally {
            session.dispose()
        }
    }
}
