package dev.lain.claudejb.integration

import dev.lain.claudejb.session.Speaker

class InterruptIntegrationTest : FakeClaudeTestBase() {

    fun `test interrupt mid-turn leaves the session idle`() {
        val session = newSessionWith("interrupt_turn.jsonl")
        session.send("do something slow")

        waitUntil("turn streaming") {
            session.transcript.entries.any { it.speaker == Speaker.ASSISTANT && it.text.contains("Working on it") }
        }

        session.interrupt()

        waitUntil("session idle after interrupt") {
            !session.turnActive &&
                session.transcript.entries.any { it.text.contains("still going") }
        }

        assertFalse("turn no longer active", session.turnActive)
    }
}
