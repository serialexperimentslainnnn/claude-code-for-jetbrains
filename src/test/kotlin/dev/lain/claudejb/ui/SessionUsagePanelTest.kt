package dev.lain.claudejb.ui

import dev.lain.claudejb.protocol.ContextUsage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure logic of [SessionUsagePanel] (no Swing instance): token humanisation, the green/amber/red
 * threshold colouring, the 0..1 context fraction (percentage vs total/max), and the reset countdown.
 * These pin the contract the painting relies on, independent of any rendering.
 */
class SessionUsagePanelTest {

    // --- formatTokens ---

    @Test
    fun `formats small counts verbatim`() {
        assertEquals("0", SessionUsagePanel.formatTokens(0))
        assertEquals("950", SessionUsagePanel.formatTokens(950))
        assertEquals("999", SessionUsagePanel.formatTokens(999))
    }

    @Test
    fun `formats thousands with a trimmed decimal`() {
        assertEquals("1k", SessionUsagePanel.formatTokens(1_000))
        assertEquals("1.2k", SessionUsagePanel.formatTokens(1_200))
        assertEquals("12.3k", SessionUsagePanel.formatTokens(12_345))
        assertEquals("999.9k", SessionUsagePanel.formatTokens(999_900))
    }

    @Test
    fun `formats millions with a trimmed decimal`() {
        assertEquals("1M", SessionUsagePanel.formatTokens(1_000_000))
        assertEquals("3.4M", SessionUsagePanel.formatTokens(3_400_000))
        assertEquals("12.5M", SessionUsagePanel.formatTokens(12_500_000))
    }

    @Test
    fun `negative counts clamp to zero`() {
        assertEquals("0", SessionUsagePanel.formatTokens(-5))
    }

    // --- thresholdColor ---

    @Test
    fun `threshold colour switches green amber red at 60 and 85`() {
        val green = SessionUsagePanel.thresholdColor(0)
        assertEquals(green, SessionUsagePanel.thresholdColor(59))
        val amber = SessionUsagePanel.thresholdColor(60)
        assertEquals(amber, SessionUsagePanel.thresholdColor(84))
        val red = SessionUsagePanel.thresholdColor(85)
        assertEquals(red, SessionUsagePanel.thresholdColor(100))
        // The three bands are distinct.
        assertTrue(green != amber && amber != red && green != red)
    }

    // --- contextFraction ---

    @Test
    fun `context fraction prefers percentage on a 0 to 100 scale`() {
        assertEquals(0.5, SessionUsagePanel.contextFraction(ContextUsage(percentage = 50.0)), 1e-9)
    }

    @Test
    fun `context fraction accepts a fractional percentage`() {
        assertEquals(0.25, SessionUsagePanel.contextFraction(ContextUsage(percentage = 0.25)), 1e-9)
    }

    @Test
    fun `context fraction falls back to total over max`() {
        val u = ContextUsage(totalTokens = 50_000, maxTokens = 200_000)
        assertEquals(0.25, SessionUsagePanel.contextFraction(u), 1e-9)
    }

    @Test
    fun `context fraction clamps to 0 and 1`() {
        assertEquals(1.0, SessionUsagePanel.contextFraction(ContextUsage(percentage = 150.0)), 1e-9)
        assertEquals(0.0, SessionUsagePanel.contextFraction(ContextUsage()), 1e-9)
    }

    // --- countdownLabel ---

    @Test
    fun `countdown is null without a window`() {
        assertNull(SessionUsagePanel.countdownLabel(null, 1_000))
    }

    @Test
    fun `countdown shows hours and minutes`() {
        // 2h 5m from now.
        val now = 10_000L
        val reset = now + 2 * 3600 + 5 * 60
        assertEquals("resets in 2h 5m", SessionUsagePanel.countdownLabel(reset, now))
    }

    @Test
    fun `countdown under an hour shows minutes only`() {
        val now = 10_000L
        assertEquals("resets in 12m", SessionUsagePanel.countdownLabel(now + 12 * 60, now))
    }

    @Test
    fun `countdown shows resetting once elapsed`() {
        assertEquals("resetting", SessionUsagePanel.countdownLabel(500, 1_000))
        assertEquals("resetting", SessionUsagePanel.countdownLabel(1_000, 1_000))
    }

    // --- resetHourLabel (absolute reset wall-clock, regression guard for the lost "Reset Hour") ---

    @Test
    fun `reset hour is null without a window`() {
        assertNull(SessionUsagePanel.resetHourLabel(null))
    }

    @Test
    fun `reset hour formats the local wall-clock as HH mm`() {
        // 2026-01-01T00:00:00Z + 9h30m, formatted in the JVM default zone.
        val epoch = java.time.Instant.parse("2026-01-01T00:00:00Z").epochSecond + 9 * 3600 + 30 * 60
        val expected = java.time.Instant.ofEpochSecond(epoch)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        assertEquals(expected, SessionUsagePanel.resetHourLabel(epoch))
    }
}
