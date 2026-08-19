package dev.lain.claudejb.integration

class RateLimitIntegrationTest : FakeClaudeTestBase() {

    fun `test rate limit event surfaces on the session`() {
        val session = newSessionWith("rate_limit.jsonl")
        session.send("status?")

        waitUntil("rate limit received") { session.rateLimit != null }

        val rl = session.rateLimit!!
        assertEquals("allowed_warning", rl.status)
        assertEquals("five_hour", rl.rateLimitType)
        assertEquals(93, rl.utilizationPercent())
    }
}
