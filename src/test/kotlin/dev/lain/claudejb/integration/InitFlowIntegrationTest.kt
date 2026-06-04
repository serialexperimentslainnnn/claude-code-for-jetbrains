package dev.lain.claudejb.integration

/**
 * End-to-end init handshake against fake-claude: starting a session spawns the binary, the `system/init`
 * line arrives on a background thread and back-fills [sessionId]/[model] onto the EDT.
 *
 * NOTE on metadata (commands/models/account): those are delivered by the binary's reply to the host's
 * `initialize` *control_request*, which (a) must echo the host-generated `request_id` and (b) requires the
 * fake to read that request off stdin. In this headless harness the spawned process's stdin is at EOF, so
 * the fake cannot consume host writes at all (see FakeClaudeTestBase). It therefore never replies to
 * `initialize` → `session.models`/`commands`/`account` stay empty here. We assert the init fields that DO
 * flow (sessionId, model). [ClaudeSession.modelOptions] now returns ONLY the binary-reported models (no
 * hand-maintained fallback), so in this harness — where the initialize reply never lands — it is empty.
 * Reported as a harness limitation in the summary.
 */
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

        // modelOptions is exactly the binary's reported list — no hand-maintained fallback that could duplicate
        // or go stale. In this harness the initialize control-reply never lands, so both are empty.
        assertEquals(session.models, session.modelOptions())
        assertTrue("no hardcoded fallback entries", session.modelOptions().none { it.value == "sonnet" || it.value == "haiku" })
    }
}
