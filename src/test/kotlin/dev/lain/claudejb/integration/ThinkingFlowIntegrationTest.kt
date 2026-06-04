package dev.lain.claudejb.integration

import dev.lain.claudejb.session.Speaker

/**
 * A turn that streams thinking deltas and finalizes a thinking + text assistant message produces both a
 * THINKING and an ASSISTANT transcript entry, reconciled from the streaming events.
 */
class ThinkingFlowIntegrationTest : FakeClaudeTestBase() {

    fun `test thinking turn yields a THINKING and an ASSISTANT entry`() {
        val session = newSessionWith("thinking_turn.jsonl")
        // send() cold-starts the (resume=false) session and flushes the prompt once the process is up.
        session.send("explain")

        // Completion signal: the finalized assistant text has landed in the transcript.
        waitUntil("assistant final text present") {
            session.transcript.entries.any { it.speaker == Speaker.ASSISTANT && it.text.contains("The answer is 42") }
        }

        val entries = session.transcript.entries
        val thinking = entries.filter { it.speaker == Speaker.THINKING }
        val assistant = entries.filter { it.speaker == Speaker.ASSISTANT }

        assertTrue("expected a THINKING entry, got: ${entries.map { it.speaker }}", thinking.isNotEmpty())
        assertTrue("THINKING text reconstructed", thinking.any { it.text.contains("reason about this carefully") })
        assertTrue("expected an ASSISTANT entry", assistant.isNotEmpty())
        assertTrue("ASSISTANT text present", assistant.any { it.text.contains("The answer is 42") })
    }
}
