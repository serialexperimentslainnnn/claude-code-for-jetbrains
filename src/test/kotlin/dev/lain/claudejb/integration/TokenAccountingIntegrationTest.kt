package dev.lain.claudejb.integration

class TokenAccountingIntegrationTest : FakeClaudeTestBase() {

    fun `test totalTokens equals the sum of all four usage components`() {
        val session = newSessionWith("token_accounting.jsonl")
        session.send("count tokens")

        waitUntil("tokens accounted") { session.totalTokens() == 380 }

        assertEquals("total tokens", 380, session.totalTokens())
        assertEquals("session input", 100, session.sessionInputTokens)
        assertEquals("session cache creation", 200, session.sessionCacheCreationTokens)
        assertEquals("session cache read", 50, session.sessionCacheReadTokens)
        assertEquals("session output", 30, session.sessionOutputTokens)
    }
}
