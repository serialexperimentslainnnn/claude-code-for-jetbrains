package dev.lain.claudejb.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RateLimitInfoTest {

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

    @Test
    fun `only rejected is exhausted`() {
        assertTrue(RateLimitInfo(status = "rejected").isExhausted)
        assertFalse(RateLimitInfo(status = "allowed_warning").isExhausted)
        assertFalse(RateLimitInfo(status = "allowed").isExhausted)
    }
}
