package dev.lain.claudejb.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [parseUsageReport] against the shape the binary really returns.
 *
 * The fixture below is a trimmed copy of a live `get_usage` reply from `claude` 2.1.222 — including the parts
 * that make it awkward: `rate_limits` mixes window objects, explicit nulls for windows that exist but have not
 * been touched, and `extra_usage`, which has an entirely different shape. Parsing it whole is what a naive
 * `@Serializable` map would attempt, and it is why this is walked by hand.
 */
class UsageReportTest {

    private fun reply(): JsonObject = buildJsonObject {
        put("subscription_type", "max")
        put("rate_limits_available", true)
        putJsonObject("rate_limits") {
            putJsonObject("five_hour") {
                put("utilization", 8)
                put("resets_at", "2026-08-06T00:10:00.281281+00:00")
            }
            putJsonObject("seven_day") {
                put("utilization", 67)
                put("resets_at", "2026-08-06T17:00:00.281309+00:00")
            }
            // Windows the plan HAS but the user has not touched come back as explicit nulls.
            put("seven_day_opus", kotlinx.serialization.json.JsonNull)
            put("seven_day_cowork", kotlinx.serialization.json.JsonNull)
            putJsonObject("extra_usage") {
                put("is_enabled", true)
                put("used_credits", 14612)
                put("currency", "EUR")
                put("decimal_places", 2)
            }
        }
    }

    /** The fixture, parsed. Fails loudly here rather than with a NullPointerException inside an assertion. */
    private fun parsed(): UsageReport = requireNotNull(parseUsageReport(reply())) { "the fixture must parse" }

    @Test
    fun `parses the windows that carry a utilization`() {
        val report = parsed()
        assertEquals(listOf("five_hour", "seven_day"), report.windows.map { it.first })
        assertEquals(8.0, report.windows[0].second.utilization)
        assertEquals(67.0, report.windows[1].second.utilization)
    }

    @Test
    fun `untouched windows are dropped, never reported as zero`() {
        // A null window means "this limit exists and you have not hit it", which a 0% bar would render as
        // indistinguishable from "measured, and you have used none". Different claims; only one is true.
        val report = parsed()
        assertTrue(report.windows.none { it.first == "seven_day_opus" })
        assertTrue(report.windows.none { it.first == "seven_day_cowork" })
    }

    @Test
    fun `session window sorts before the weekly one regardless of wire order`() {
        // Order is meaning in this list: the dashboard renders it top-down, and the window a user is most
        // likely to hit in the next hour belongs first.
        assertEquals("five_hour", parsed().windows.first().first)
    }

    @Test
    fun `extra_usage is parsed as credits, not as a window`() {
        val report = parsed()
        val extra = requireNotNull(report.extra)
        assertEquals(14612.0, extra.usedCredits)
        assertEquals("EUR", extra.currency)
        assertTrue(report.windows.none { it.first == "extra_usage" })
    }

    @Test
    fun `a null payload or one with nothing to show yields null`() {
        assertNull(parseUsageReport(null))
        assertNull(parseUsageReport(buildJsonObject { put("subscription_type", "max") }))
    }

    @Test
    fun `an unrecognised window shape is skipped, not thrown on`() {
        // A newer binary can add a window whose value is not an object. This feeds a dashboard: one unknown
        // key must not blank the whole panel.
        val hostile = buildJsonObject {
            put("rate_limits_available", true)
            putJsonObject("rate_limits") {
                put("five_hour", "unexpected-string")
                putJsonObject("seven_day") { put("utilization", 50) }
            }
        }
        val report = requireNotNull(parseUsageReport(hostile))
        assertEquals(listOf("seven_day"), report.windows.map { it.first })
    }

    @Test
    fun `window titles are descriptive, and the composer pill keeps its short labels`() {
        // Two separate label sets on purpose: collapsing them once broke the composer pill, which has room for
        // "5h" and not for "Current session".
        assertEquals("Current session", RateLimitInfo.windowTitleFor("five_hour"))
        assertEquals("All models", RateLimitInfo.windowTitleFor("seven_day"))
        assertEquals("5h", RateLimitInfo(rateLimitType = "five_hour").windowLabel())

        // An unknown window is still a window the user is limited by — label it rather than hide it.
        assertEquals("Cowork", RateLimitInfo.windowTitleFor("seven_day_cowork"))
    }
}
