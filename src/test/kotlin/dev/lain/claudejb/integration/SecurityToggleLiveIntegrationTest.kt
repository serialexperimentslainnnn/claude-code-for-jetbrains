package dev.lain.claudejb.integration

import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.session.Speaker
import dev.lain.claudejb.settings.ClaudeSettings

/**
 * Real-world regression: a user reported that flipping a Security toggle (Settings ▸ Claude Code ▸ Security)
 * required an IDE restart to take effect. [ClaudeSettings.sensitivePolicy] reads the stored rule set fresh
 * on every call — no snapshot is cached anywhere — so this drives a REAL, already-running [ClaudeSession]
 * through two identical dangerous-command `can_use_tool` requests from an untrusted (MCP-shaped) caller, with
 * the rule switched off directly on the live settings object in between (exactly what
 * `ClaudeSettingsConfigurable.apply()` does) — no new session, no process restart, no IDE restart.
 */
class SecurityToggleLiveIntegrationTest : FakeClaudeTestBase() {

    fun `test flipping a security toggle mid-session changes the very next verdict, no restart of anything`() {
        val session = newSessionWith("security_toggle_live.jsonl")
        val settings = ClaudeSettings.getInstance(project)
        assertEquals("nothing disabled by default", "", settings.state.disabledSecurityRules)

        session.send("run something") // cold-starts the session

        // First dangerous-command call from an untrusted (MCP-shaped) tool: enforced by default → silently
        // DENIED (no pending permission ever appears; onSensitiveDenied posts a transcript notice instead).
        waitUntil("first call silently denied") {
            session.transcript.entries.any {
                it.speaker == Speaker.SYSTEM && it.text.contains("Blocked mcp__test__run")
            }
        }
        assertTrue("no card for the denied call", session.pendingPermissions().isEmpty())

        // Switch the rule off on the LIVE settings object — exactly what apply() does, no session/process touched.
        settings.state.disabledSecurityRules = SecurityRule.SECRET_DUMPING_COMMANDS.name

        // Second, identical call: same session, same still-running process, same PermissionBroker instance —
        // only the setting changed. It must now be downgraded to ASK (a permission card), not silently denied.
        waitUntil("second call is now a permission card, not a silent deny") {
            session.pendingPermissions().any { it.toolName == "mcp__test__run" }
        }
        val pending = session.pendingPermissions().single()
        assertEquals("fake_sec_2", pending.requestId)
    }
}
