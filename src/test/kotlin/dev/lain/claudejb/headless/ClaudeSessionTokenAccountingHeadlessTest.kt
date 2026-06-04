package dev.lain.claudejb.headless

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.protocol.ClaudeEvent
import dev.lain.claudejb.session.ClaudeSession

/**
 * Headless: token accounting of [ClaudeSession] WITHOUT starting the binary, driven through the
 * [ClaudeSession.handleEventForTest] seam. Verifies that every usage component (input, cache-creation,
 * cache-read, output) is counted, that a message boundary folds the live counters into the session
 * totals, and that totals accumulate across messages — the regression that previously under-reported
 * usage by discarding cache_creation_input_tokens.
 */
class ClaudeSessionTokenAccountingHeadlessTest : BasePlatformTestCase() {

    private fun flush() = PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

    fun `test fresh session reports zero tokens`() {
        val session = ClaudeSession(project, "t")
        try {
            assertEquals(0, session.totalTokens())
            assertEquals(0, session.liveInputTokens)
            assertEquals(0, session.sessionOutputTokens)
        } finally {
            session.dispose()
        }
    }

    fun `test LiveUsage counts all four components into the live totals`() {
        val session = ClaudeSession(project, "t")
        try {
            session.handleEventForTest(
                ClaudeEvent.LiveUsage(inputTokens = 12, cacheCreationTokens = 1024, cacheReadTokens = 7, outputTokens = 3)
            )
            flush()
            assertEquals(12, session.liveInputTokens)
            assertEquals(1024, session.liveCacheCreationTokens)
            assertEquals(7, session.liveCacheReadTokens)
            assertEquals(3, session.liveOutputTokens)
            // 12 + 1024 + 7 + 3
            assertEquals(1046, session.totalTokens())
        } finally {
            session.dispose()
        }
    }

    fun `test MessageStart folds live tokens into session and accumulates across messages`() {
        val session = ClaudeSession(project, "t")
        try {
            // First message's usage.
            session.handleEventForTest(
                ClaudeEvent.LiveUsage(inputTokens = 10, cacheCreationTokens = 100, cacheReadTokens = 0, outputTokens = 5)
            )
            flush()
            assertEquals(115, session.totalTokens())

            // A new message starts: the finished message's tokens fold into the session totals, live resets.
            session.handleEventForTest(ClaudeEvent.MessageStart)
            flush()
            assertEquals(0, session.liveOutputTokens)
            assertEquals(115, session.sessionInputTokens + session.sessionCacheCreationTokens + session.sessionCacheReadTokens + session.sessionOutputTokens)
            assertEquals(115, session.totalTokens())

            // Second message's usage adds on top — total must reflect BOTH messages, not just the latest.
            session.handleEventForTest(
                ClaudeEvent.LiveUsage(inputTokens = 20, cacheCreationTokens = 0, cacheReadTokens = 50, outputTokens = 8)
            )
            flush()
            assertEquals(115 + 78, session.totalTokens())
        } finally {
            session.dispose()
        }
    }
}
