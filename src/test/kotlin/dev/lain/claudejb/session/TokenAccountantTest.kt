package dev.lain.claudejb.session

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure unit tests for [TokenAccountant], replicating the cases of the headless
 * `ClaudeSessionTokenAccountingHeadlessTest` without an IDE fixture: every usage component is counted,
 * a message boundary folds the live counters into the session totals and resets live, and totals
 * accumulate across messages (the regression that previously under-reported cache_creation_input_tokens).
 */
class TokenAccountantTest {

    @Test
    fun `fresh accountant reports zero tokens`() {
        val acc = TokenAccountant()
        assertEquals(0, acc.totalTokens())
        assertEquals(0, acc.liveInputTokens)
        assertEquals(0, acc.sessionOutputTokens)
    }

    @Test
    fun `onLiveUsage counts all four components into the live totals`() {
        val acc = TokenAccountant()
        acc.onLiveUsage(input = 12, cacheCreation = 1024, cacheRead = 7, output = 3)

        assertEquals(12, acc.liveInputTokens)
        assertEquals(1024, acc.liveCacheCreationTokens)
        assertEquals(7, acc.liveCacheReadTokens)
        assertEquals(3, acc.liveOutputTokens)
        // 12 + 1024 + 7 + 3
        assertEquals(1046, acc.totalTokens())
    }

    @Test
    fun `onLiveUsage replaces rather than adds`() {
        val acc = TokenAccountant()
        acc.onLiveUsage(input = 100, cacheCreation = 200, cacheRead = 300, output = 400)
        // A later snapshot of the SAME in-flight message overwrites the live counters.
        acc.onLiveUsage(input = 1, cacheCreation = 2, cacheRead = 3, output = 4)

        assertEquals(1, acc.liveInputTokens)
        assertEquals(2, acc.liveCacheCreationTokens)
        assertEquals(3, acc.liveCacheReadTokens)
        assertEquals(4, acc.liveOutputTokens)
        assertEquals(10, acc.totalTokens())
    }

    @Test
    fun `foldIntoSession moves live to session and resets live, accumulating across messages`() {
        val acc = TokenAccountant()
        // First message's usage.
        acc.onLiveUsage(input = 10, cacheCreation = 100, cacheRead = 0, output = 5)
        assertEquals(115, acc.totalTokens())

        // A new message starts: the finished message's tokens fold into the session totals, live resets.
        acc.foldIntoSession()
        assertEquals(0, acc.liveInputTokens)
        assertEquals(0, acc.liveCacheCreationTokens)
        assertEquals(0, acc.liveCacheReadTokens)
        assertEquals(0, acc.liveOutputTokens)
        assertEquals(10, acc.sessionInputTokens)
        assertEquals(100, acc.sessionCacheCreationTokens)
        assertEquals(0, acc.sessionCacheReadTokens)
        assertEquals(5, acc.sessionOutputTokens)
        assertEquals(
            115,
            acc.sessionInputTokens + acc.sessionCacheCreationTokens + acc.sessionCacheReadTokens + acc.sessionOutputTokens,
        )
        assertEquals(115, acc.totalTokens())

        // Second message's usage adds on top — total must reflect BOTH messages, not just the latest.
        acc.onLiveUsage(input = 20, cacheCreation = 0, cacheRead = 50, output = 8)
        assertEquals(115 + 78, acc.totalTokens())

        // Folding the second message accumulates each component into the session totals.
        acc.foldIntoSession()
        assertEquals(30, acc.sessionInputTokens)
        assertEquals(100, acc.sessionCacheCreationTokens)
        assertEquals(50, acc.sessionCacheReadTokens)
        assertEquals(13, acc.sessionOutputTokens)
        assertEquals(193, acc.totalTokens())
    }

    @Test
    fun `foldIntoSession on empty live is a no-op for session totals`() {
        val acc = TokenAccountant()
        acc.onLiveUsage(input = 1, cacheCreation = 2, cacheRead = 3, output = 4)
        acc.foldIntoSession()
        val before = acc.totalTokens()
        acc.foldIntoSession() // nothing live to fold
        assertEquals(before, acc.totalTokens())
        assertEquals(10, acc.totalTokens())
    }

    @Test
    fun `reset clears live and session counters`() {
        val acc = TokenAccountant()
        acc.onLiveUsage(input = 10, cacheCreation = 20, cacheRead = 30, output = 40)
        acc.foldIntoSession()
        acc.onLiveUsage(input = 1, cacheCreation = 1, cacheRead = 1, output = 1)

        acc.reset()

        assertEquals(0, acc.liveInputTokens)
        assertEquals(0, acc.liveCacheCreationTokens)
        assertEquals(0, acc.liveCacheReadTokens)
        assertEquals(0, acc.liveOutputTokens)
        assertEquals(0, acc.sessionInputTokens)
        assertEquals(0, acc.sessionCacheCreationTokens)
        assertEquals(0, acc.sessionCacheReadTokens)
        assertEquals(0, acc.sessionOutputTokens)
        assertEquals(0, acc.totalTokens())
    }
}
