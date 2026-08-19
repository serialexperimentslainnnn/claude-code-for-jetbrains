package dev.lain.claudejb.integration

import dev.lain.claudejb.session.Speaker

class WriteUnsafeContextRegressionIntegrationTest : FakeClaudeTestBase() {

    fun `test cascaded writes process without crashing`() {
        val session = newSessionWith("write_cascade_accept.jsonl")
        session.send("write three files")

        waitUntil("cascade finished", timeoutMs = 20_000) {
            session.pendingPermissions().forEach { session.resolvePermission(it.requestId, allow = true) }
            session.transcript.entries.any { it.speaker == Speaker.ASSISTANT && it.text.contains("All three files written") }
        }

        val entries = session.transcript.entries
        val toolRows = entries.filter { it.speaker == Speaker.TOOL }
        val toolOutputs = entries.filter { it.speaker == Speaker.TOOL_OUTPUT }
        assertEquals("three Write tool rows", 3, toolRows.size)
        assertEquals("three tool outputs", 3, toolOutputs.size)
        listOf("toolu_c1", "toolu_c2", "toolu_c3").forEach { id ->
            assertTrue("TOOL row $id", toolRows.any { it.toolUseId == id })
            assertTrue("TOOL_OUTPUT for $id", toolOutputs.any { it.toolUseId == id })
        }
    }
}
