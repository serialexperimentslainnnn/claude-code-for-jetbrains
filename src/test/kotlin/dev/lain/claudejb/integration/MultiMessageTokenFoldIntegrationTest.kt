package dev.lain.claudejb.integration

/**
 * A turn that emits two assistant messages: message_delta usage restarts near zero per message, so the first
 * message's tokens must be folded into the session total at the second message_start (and again at result),
 * otherwise only the last message would count.
 *
 * msg1: input=80, output=20. msg2: input=10, output=40. → session input=90, output=60, total=150.
 */
class MultiMessageTokenFoldIntegrationTest : FakeClaudeTestBase() {

    fun `test tokens from both assistant messages accumulate`() {
        val session = newSessionWith("multi_message.jsonl")
        session.send("two parts please") // cold-starts the session

        waitUntil("both messages folded") { session.totalTokens() == 150 }

        assertEquals("total tokens (both messages)", 150, session.totalTokens())
        assertEquals("session input (folded)", 90, session.sessionInputTokens)
        assertEquals("session output (folded)", 60, session.sessionOutputTokens)
    }
}
