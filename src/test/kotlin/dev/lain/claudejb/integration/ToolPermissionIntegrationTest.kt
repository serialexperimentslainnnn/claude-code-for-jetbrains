package dev.lain.claudejb.integration

import dev.lain.claudejb.session.Speaker

class ToolPermissionIntegrationTest : FakeClaudeTestBase() {

    fun `test pending permission is surfaced and resolves into a tool result`() {
        val session = newSessionWith("tool_use_permission.jsonl")
        session.send("write a file")

        waitUntil("permission requested") { session.pendingPermissions().size == 1 }
        val pending = session.pendingPermissions().single()
        assertEquals("Write", pending.toolName)
        assertEquals("fake_perm_1", pending.requestId)
        assertTrue("Write is reviewable", pending.reviewable)

        session.resolvePermission(pending.requestId, allow = true)

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
