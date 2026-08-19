package dev.lain.claudejb.session

import org.junit.Assert.assertEquals
import org.junit.Test

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
        assertEquals(1046, acc.totalTokens())
    }

    @Test
    fun `onLiveUsage replaces rather than adds`() {
        val acc = TokenAccountant()
        acc.onLiveUsage(input = 100, cacheCreation = 200, cacheRead = 300, output = 400)
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
        acc.onLiveUsage(input = 10, cacheCreation = 100, cacheRead = 0, output = 5)
        assertEquals(115, acc.totalTokens())

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

        acc.onLiveUsage(input = 20, cacheCreation = 0, cacheRead = 50, output = 8)
        assertEquals(115 + 78, acc.totalTokens())

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
        acc.foldIntoSession()
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
