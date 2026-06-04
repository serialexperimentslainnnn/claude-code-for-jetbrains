package dev.lain.claudejb.integration

/**
 * Token bookkeeping across a single turn: message_start seeds the four live counters (input / cache_creation
 * / cache_read / output), message_delta updates them, and `result` folds them into the session totals.
 *
 * Fixture values: input=100, cache_creation=200, cache_read=50, output=30 → total 380.
 */
class TokenAccountingIntegrationTest : FakeClaudeTestBase() {

    fun `test totalTokens equals the sum of all four usage components`() {
        val session = newSessionWith("token_accounting.jsonl")
        session.send("count tokens") // cold-starts the session

        waitUntil("tokens accounted") { session.totalTokens() == 380 }

        assertEquals("total tokens", 380, session.totalTokens())
        // After result, the live counters are folded into the session totals (live reset to 0).
        assertEquals("session input", 100, session.sessionInputTokens)
        assertEquals("session cache creation", 200, session.sessionCacheCreationTokens)
        assertEquals("session cache read", 50, session.sessionCacheReadTokens)
        assertEquals("session output", 30, session.sessionOutputTokens)
    }
}
