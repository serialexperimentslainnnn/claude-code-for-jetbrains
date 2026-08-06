package dev.lain.claudejb.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure logic of [RateLimitInfo]. The wire reports utilization as a 0..100 percentage (the SDK documents
 * every `get_usage` window as "Percentage of the window used, 0-100"); the UI quota bar depends on this
 * normalization, so these tests pin the contract independently of the wire decoding (which
 * ProtocolParserTest covers).
 *
 * These tests used to pin the OPPOSITE at the low end: a "two scales" heuristic multiplied anything
 * `<= 1.0` by 100, and the test named `utilization exactly 1 is treated as the fractional scale` froze
 * that guess as if it were the contract. A live session at a genuine 1% rendered as 100% — the guess is
 * undecidable exactly at 1.0, and every freshly reset window climbs through that range.
 */
class RateLimitInfoTest {

    // --- utilizationPercent ---

    @Test
    fun `utilization is a 0 to 100 percentage, passed through`() {
        assertEquals(92, RateLimitInfo(utilization = 92.0).utilizationPercent())
    }

    @Test
    fun `utilization 1 means 1 percent, not a full window`() {
        assertEquals(1, RateLimitInfo(utilization = 1.0).utilizationPercent())
    }

    @Test
    fun `sub-percent utilization rounds instead of inflating`() {
        assertEquals(1, RateLimitInfo(utilization = 0.5).utilizationPercent())
        assertEquals(0, RateLimitInfo(utilization = 0.2).utilizationPercent())
    }

    @Test
    fun `null utilization yields null`() {
        assertNull(RateLimitInfo(utilization = null).utilizationPercent())
    }

    @Test
    fun `utilization is clamped to 0 and 100`() {
        assertEquals(100, RateLimitInfo(utilization = 150.0).utilizationPercent())
        assertEquals(0, RateLimitInfo(utilization = -5.0).utilizationPercent())
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
