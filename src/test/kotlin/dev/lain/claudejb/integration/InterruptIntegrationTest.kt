package dev.lain.claudejb.integration

import dev.lain.claudejb.session.Speaker

/**
 * Calling [ClaudeSession.interrupt] mid-turn must not hang or throw: the interrupt control_request is written
 * to the binary and the session stays responsive, converging to idle once the turn's `result` arrives.
 *
 * NOTE: the fake binary has no model loop to truncate, so it doesn't stop early on interrupt; this asserts the
 * host side stays alive and reaches idle (turnActive=false), which is the regression we care about. The
 * fixture sleeps mid-stream so there is a real window in which the turn is active when interrupt() fires.
 */
class InterruptIntegrationTest : FakeClaudeTestBase() {

    fun `test interrupt mid-turn leaves the session idle`() {
        val session = newSessionWith("interrupt_turn.jsonl")
        session.send("do something slow") // cold-starts the session

        // Wait until the streaming text has started (turn genuinely in flight) before interrupting.
        waitUntil("turn streaming") {
            session.transcript.entries.any { it.speaker == Speaker.ASSISTANT && it.text.contains("Working on it") }
        }

        // Must not block or throw.
        session.interrupt()

        // The turn drains and the session converges to idle.
        waitUntil("session idle after interrupt") {
            !session.turnActive &&
                session.transcript.entries.any { it.text.contains("still going") }
        }

        assertFalse("turn no longer active", session.turnActive)
    }
}
