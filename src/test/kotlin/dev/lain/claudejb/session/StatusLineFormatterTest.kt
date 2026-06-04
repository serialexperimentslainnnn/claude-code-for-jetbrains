package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Pure tests for the live reasoning-token status suffix (coarse bucketing, blank at zero). */
class StatusLineFormatterTest {

    @Test
    fun `zero or negative is blank`() {
        assertEquals("", StatusLineFormatter.thinkingSuffix(0))
        assertEquals("", StatusLineFormatter.thinkingSuffix(-5))
    }

    @Test
    fun `sub-thousand rounds to a coarse bucket`() {
        assertEquals("~850 reasoning tokens", StatusLineFormatter.thinkingSuffix(850))
        assertEquals("~100 reasoning tokens", StatusLineFormatter.thinkingSuffix(120))
    }

    @Test
    fun `thousands are compact with one decimal under 10k`() {
        assertEquals("~1.2k reasoning tokens", StatusLineFormatter.thinkingSuffix(1240))
    }

    @Test
    fun `tens of thousands drop the decimal`() {
        assertEquals("~23k reasoning tokens", StatusLineFormatter.thinkingSuffix(23800))
    }
}
