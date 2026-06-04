package dev.lain.claudejb.integration

import dev.lain.claudejb.session.Speaker

/**
 * Full can_use_tool round-trip in default mode: a Write tool_use produces a `control_request` that the broker
 * surfaces as a pending permission (no auto-approval in default mode). Approving it writes the `allow`
 * control_response; the fake then emits the tool_result, which lands as a TOOL_OUTPUT anchored to the TOOL row.
 */
class ToolPermissionIntegrationTest : FakeClaudeTestBase() {

    fun `test pending permission is surfaced and resolves into a tool result`() {
        val session = newSessionWith("tool_use_permission.jsonl")
        session.send("write a file") // cold-starts the session

        waitUntil("permission requested") { session.pendingPermissions().size == 1 }
        val pending = session.pendingPermissions().single()
        assertEquals("Write", pending.toolName)
        assertEquals("fake_perm_1", pending.requestId)
        assertTrue("Write is reviewable", pending.reviewable)

        session.resolvePermission(pending.requestId, allow = true)

        // After allow, the fake emits the tool_result → TOOL_OUTPUT, then a final assistant text.
        waitUntil("tool result anchored") {
            session.transcript.entries.any { it.speaker == Speaker.TOOL_OUTPUT && it.toolUseId == "toolu_write_1" }
        }

        val entries = session.transcript.entries
        assertTrue("permission cleared", session.pendingPermissions().isEmpty())
        assertTrue("TOOL row present", entries.any { it.speaker == Speaker.TOOL && it.toolUseId == "toolu_write_1" })
        assertTrue(
            "final assistant text present",
            entries.any { it.speaker == Speaker.ASSISTANT && it.text.contains("Done, wrote the file") },
        )
    }
}
