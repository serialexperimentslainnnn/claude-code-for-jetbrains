package dev.lain.claudejb.integration

class MultiMessageTokenFoldIntegrationTest : FakeClaudeTestBase() {

    fun `test tokens from both assistant messages accumulate`() {
        val session = newSessionWith("multi_message.jsonl")
        session.send("two parts please")

        waitUntil("both messages folded") { session.totalTokens() == 150 }

        assertEquals("total tokens (both messages)", 150, session.totalTokens())
        assertEquals("session input (folded)", 90, session.sessionInputTokens)
        assertEquals("session output (folded)", 60, session.sessionOutputTokens)
    }
}
