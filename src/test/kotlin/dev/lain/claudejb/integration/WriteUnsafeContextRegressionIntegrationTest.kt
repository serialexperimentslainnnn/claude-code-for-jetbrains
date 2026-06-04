package dev.lain.claudejb.integration

import dev.lain.claudejb.session.Speaker

/**
 * Regression for the "Write-unsafe context" crash: a cascade of three Write tool calls in one turn must
 * process cleanly and leave a consistent transcript — three TOOL rows, three TOOL_OUTPUT rows (each anchored
 * to its call), and the closing assistant text — without throwing.
 *
 * Driven in default mode and resolving each permission as it surfaces (the broker's snapshot/resolve path is
 * what the original crash lived on). Auto-approval under acceptEdits is path-confined to the project root
 * ([DiffPresenter.isWithinRoot]); reproducing that branch needs real absolute in-root paths, which a static
 * fixture can't bake — so we exercise the equivalent resolve path explicitly here instead.
 */
class WriteUnsafeContextRegressionIntegrationTest : FakeClaudeTestBase() {

    fun `test cascaded writes process without crashing`() {
        val session = newSessionWith("write_cascade_accept.jsonl")
        session.send("write three files") // cold-starts the session

        // Resolve any permission the cascade surfaces, polling until the turn's final text lands.
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
