package dev.lain.claudejb.headless

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.controller.session.ClaudeSession
import dev.lain.claudejb.model.protocol.ClaudeEvent

class ClaudeSessionTokenAccountingHeadlessTest : BasePlatformTestCase() {

    private fun flush() = PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

    fun `test fresh session reports zero tokens`() {
        val session = ClaudeSession(project, "t")
        try {
            assertEquals(0, session.tokens.totalTokens())
            assertEquals(0, session.tokens.liveInputTokens)
            assertEquals(0, session.tokens.sessionOutputTokens)
        } finally {
            session.dispose()
        }
    }

    fun `test LiveUsage counts all four components into the live totals`() {
        val session = ClaudeSession(project, "t")
        try {
            session.handleEventForTest(
                ClaudeEvent.LiveUsage(inputTokens = 12, cacheCreationTokens = 1024, cacheReadTokens = 7, outputTokens = 3),
            )
            flush()
            val t = session.tokens
            assertEquals(12, t.liveInputTokens)
            assertEquals(1024, t.liveCacheCreationTokens)
            assertEquals(7, t.liveCacheReadTokens)
            assertEquals(3, t.liveOutputTokens)
            assertEquals(1046, t.totalTokens())
        } finally {
            session.dispose()
        }
    }

    fun `test MessageStart folds live tokens into session and accumulates across messages`() {
        val session = ClaudeSession(project, "t")
        try {
            session.handleEventForTest(
                ClaudeEvent.LiveUsage(inputTokens = 10, cacheCreationTokens = 100, cacheReadTokens = 0, outputTokens = 5),
            )
            flush()
            val t = session.tokens
            assertEquals(115, t.totalTokens())

            session.handleEventForTest(ClaudeEvent.MessageStart)
            flush()
            assertEquals(0, t.liveOutputTokens)
            assertEquals(115, t.sessionInputTokens + t.sessionCacheCreationTokens + t.sessionCacheReadTokens + t.sessionOutputTokens)
            assertEquals(115, t.totalTokens())

            session.handleEventForTest(
                ClaudeEvent.LiveUsage(inputTokens = 20, cacheCreationTokens = 0, cacheReadTokens = 50, outputTokens = 8),
            )
            flush()
            assertEquals(115 + 78, t.totalTokens())
        } finally {
            session.dispose()
        }
    }
}
