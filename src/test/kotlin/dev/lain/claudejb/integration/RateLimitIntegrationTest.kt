package dev.lain.claudejb.integration

/**
 * A `rate_limit_event` on the stream is decoded into [RateLimitInfo] and exposed via [ClaudeSession.rateLimit]
 * (drives the quota bar). The fixture reports a five-hour window at `utilization: 0.925` — the FRACTION the
 * binary really sends (captured live on 2.1.223), not a 0..100 percentage.
 */
class RateLimitIntegrationTest : FakeClaudeTestBase() {

    fun `test rate limit event surfaces on the session`() {
        val session = newSessionWith("rate_limit.jsonl")
        session.send("status?") // cold-starts the session

        waitUntil("rate limit received") { session.rateLimit != null }

        val rl = session.rateLimit!!
        assertEquals("allowed_warning", rl.status)
        assertEquals("five_hour", rl.rateLimitType)
        assertEquals(93, rl.utilizationPercent()) // 0.925 -> 92.5% -> rounds to 93
    }
}
