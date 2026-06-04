package dev.lain.claudejb.integration

/**
 * A `rate_limit_event` on the stream is decoded into [RateLimitInfo] and exposed via [ClaudeSession.rateLimit]
 * (drives the quota bar). Fixture reports a five-hour window at 92.5% utilization with a warning status.
 */
class RateLimitIntegrationTest : FakeClaudeTestBase() {

    fun `test rate limit event surfaces on the session`() {
        val session = newSessionWith("rate_limit.jsonl")
        session.send("status?") // cold-starts the session

        waitUntil("rate limit received") { session.rateLimit != null }

        val rl = session.rateLimit!!
        assertEquals("allowed_warning", rl.status)
        assertTrue("isWarning", rl.isWarning)
        assertEquals("five_hour", rl.rateLimitType)
        assertEquals("5h", rl.windowLabel())
        assertEquals(92, rl.utilizationPercent())
    }
}
