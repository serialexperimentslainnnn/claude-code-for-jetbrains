package dev.lain.claudejb.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure logic of [RateLimitInfo]. The UI quota bar depends on this normalization, so the contract is pinned
 * here independently of the wire decoding (which ProtocolParserTest covers).
 *
 * THE SCALE IS A 0..1 FRACTION, and it is NOT the same as `get_usage`'s. Both previous versions of this
 * file were wrong in opposite directions, which is why the source is a live capture rather than a reading
 * of the types — `claude` 2.1.223, with claude.ai showing 92% of the weekly window spent:
 *
 * ```
 * {"status":"allowed_warning","rateLimitType":"seven_day","utilization":0.92,"surpassedThreshold":0.75}
 * ```
 *
 * `sdk.d.ts` documents "Percentage of the window used, 0-100" only on the `get_usage` windows;
 * `SDKRateLimitInfo.utilization` says nothing, and assuming it matched turned a 92% window into a 1% bar.
 */
class RateLimitInfoTest {

    // --- utilizationPercent ---

    @Test
    fun `the live capture — 0_92 is 92 percent`() {
        assertEquals(92, RateLimitInfo(utilization = 0.92).utilizationPercent())
    }

    @Test
    fun `a full window is 1_0, not 100`() {
        assertEquals(100, RateLimitInfo(utilization = 1.0).utilizationPercent())
    }

    @Test
    fun `a freshly reset window reads low, not full`() {
        // The regression this replaces: 0.01 read on the 0..100 scale rounded to 0 and, before that, a
        // "<= 1.0 means fraction" guess turned a genuine 1 into 100.
        assertEquals(1, RateLimitInfo(utilization = 0.01).utilizationPercent())
        assertEquals(6, RateLimitInfo(utilization = 0.06).utilizationPercent())
    }

    @Test
    fun `null utilization yields null`() {
        assertNull(RateLimitInfo(utilization = null).utilizationPercent())
    }

    @Test
    fun `utilization is clamped to 0 and 100`() {
        assertEquals(100, RateLimitInfo(utilization = 1.5).utilizationPercent())
        assertEquals(0, RateLimitInfo(utilization = -0.5).utilizationPercent())
    }

    // --- status flags ---

    @Test
    fun `allowed_warning is a warning but not exhausted`() {
        val info = RateLimitInfo(status = "allowed_warning")
        assertTrue(info.isWarning)
        assertFalse(info.isExhausted)
    }

    @Test
    fun `rejected is both warning and exhausted`() {
        val info = RateLimitInfo(status = "rejected")
        assertTrue(info.isWarning)
        assertTrue(info.isExhausted)
    }

    @Test
    fun `allowed is neither warning nor exhausted`() {
        val info = RateLimitInfo(status = "allowed")
        assertFalse(info.isWarning)
        assertFalse(info.isExhausted)
    }

    // --- windowLabel ---

    @Test
    fun `window labels map known rate limit types`() {
        assertEquals("5h", RateLimitInfo(rateLimitType = "five_hour").windowLabel())
        assertEquals("7d", RateLimitInfo(rateLimitType = "seven_day").windowLabel())
        assertEquals("7d Opus", RateLimitInfo(rateLimitType = "seven_day_opus").windowLabel())
        assertEquals("7d Sonnet", RateLimitInfo(rateLimitType = "seven_day_sonnet").windowLabel())
        assertEquals("overage", RateLimitInfo(rateLimitType = "overage").windowLabel())
    }

    @Test
    fun `unknown or null rate limit type falls back to quota`() {
        assertEquals("quota", RateLimitInfo(rateLimitType = "something_new").windowLabel())
        assertEquals("quota", RateLimitInfo(rateLimitType = null).windowLabel())
    }
}
