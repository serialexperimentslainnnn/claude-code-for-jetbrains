package dev.lain.claudejb.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GuardAlertRetentionTest {

    private val day = 24L * 60 * 60 * 1000

    private fun alertAt(at: Long) = GuardAlert(
        at = at,
        rule = "OUTSIDE_PROJECT",
        category = "FILESYSTEM_BOUNDARY",
        verdict = GuardAlert.DENIED,
    )

    @Test
    fun `an alert older than the window is dropped, one on the edge is kept`() {
        val now = 100 * day
        val kept = GuardAlertLog.retained(
            listOf(alertAt(now - 31 * day), alertAt(now - 30 * day), alertAt(now)),
            retentionDays = 30,
            nowMillis = now,
        )

        assertEquals(listOf(now - 30 * day, now), kept.map { it.at })
    }

    @Test
    fun `keeping until the log is full drops nothing by age`() {
        val alerts = listOf(alertAt(0), alertAt(1), alertAt(500 * day))

        assertEquals(alerts, GuardAlertLog.retained(alerts, GuardAlertLog.KEEP_UNTIL_FULL, 500 * day))
    }

    @Test
    fun `a negative window is read as no window at all, never as dropping everything`() {
        val alerts = listOf(alertAt(day), alertAt(2 * day))

        assertEquals(alerts, GuardAlertLog.retained(alerts, retentionDays = -7, nowMillis = 900 * day))
    }
}
