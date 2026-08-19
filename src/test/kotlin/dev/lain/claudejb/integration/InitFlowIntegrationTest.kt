package dev.lain.claudejb.integration

class InitFlowIntegrationTest : FakeClaudeTestBase() {

    fun `test system init back-fills sessionId and model`() {
        val session = newSessionWith("init_basic.jsonl")
        session.start(resume = false)

        waitUntil("sessionId populated from system/init") {
            session.sessionId == "11111111-1111-1111-1111-111111111111"
        }
        assertEquals("11111111-1111-1111-1111-111111111111", session.sessionId)
        assertEquals("claude-opus-4-8", session.model)
    }

    fun `test modelOptions reflects only binary-reported models (no hardcoded fallback)`() {
        val session = newSessionWith("init_metadata.jsonl")
        session.start(resume = false)
        waitUntil("session id back-filled from init") { session.sessionId != null }

        assertEquals(session.models, session.modelOptions())
        assertTrue("no hardcoded fallback entries", session.modelOptions().none { it.value == "sonnet" || it.value == "haiku" })
    }
}
