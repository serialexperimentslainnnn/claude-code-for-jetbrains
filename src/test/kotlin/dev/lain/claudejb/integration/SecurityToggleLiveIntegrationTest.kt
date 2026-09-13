package dev.lain.claudejb.integration

import dev.lain.claudejb.model.permission.vocab.SecurityRule
import dev.lain.claudejb.model.session.transcript.Speaker
import dev.lain.claudejb.model.settings.ClaudeSettings

class SecurityToggleLiveIntegrationTest : FakeClaudeTestBase() {

    fun `test flipping a security toggle mid-session changes the very next verdict, no restart of anything`() {
        val session = newSessionWith("security_toggle_live.jsonl")
        val settings = ClaudeSettings.getInstance(project)
        assertEquals("nothing disabled by default", "", settings.state.disabledSecurityRules)

        session.send("run something")

        waitUntil("first call silently denied") {
            session.transcript.entries.any {
                it.speaker == Speaker.SYSTEM && it.text.contains("Blocked mcp__test__run")
            }
        }
        assertTrue("no card for the denied call", session.cards.pending().isEmpty())

        settings.state.disabledSecurityRules = SecurityRule.SECRET_DUMPING_COMMANDS.name

        waitUntil("second call is now a permission card, not a silent deny") {
            session.cards.pending().any { it.toolName == "mcp__test__run" }
        }
        val pending = session.cards.pending().single()
        assertEquals("fake_sec_2", pending.requestId)
    }
}
