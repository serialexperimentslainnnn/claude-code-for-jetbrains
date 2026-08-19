package dev.lain.claudejb.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

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
        val report = parsed()
        assertTrue(report.windows.none { it.first == "seven_day_opus" })
        assertTrue(report.windows.none { it.first == "seven_day_cowork" })
    }

    @Test
    fun `session window sorts before the weekly one regardless of wire order`() {
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
    fun `window titles are descriptive, and there is only one set of them`() {
        assertEquals("Current session", RateLimitInfo.windowTitleFor("five_hour"))
        assertEquals("All models", RateLimitInfo.windowTitleFor("seven_day"))

        assertEquals("Cowork", RateLimitInfo.windowTitleFor("seven_day_cowork"))
    }

    @Test
    fun `nimbus_quill is dropped, and every other unknown window is still shown`() {
        val payload = buildJsonObject {
            put("rate_limits_available", true)
            putJsonObject("rate_limits") {
                putJsonObject("five_hour") { put("utilization", 4) }
                putJsonObject("nimbus_quill") { put("utilization", 0) }
                putJsonObject("some_future_window") { put("utilization", 12) }
            }
        }
        val report = requireNotNull(parseUsageReport(payload))
        assertEquals(listOf("five_hour", "some_future_window"), report.windows.map { it.first })
    }

    private fun withModelScoped(vararg entries: JsonObject): UsageReport = requireNotNull(
        parseUsageReport(
            buildJsonObject {
                put("rate_limits_available", true)
                putJsonObject("rate_limits") {
                    putJsonObject("five_hour") { put("utilization", 13) }
                    putJsonArray("model_scoped") { entries.forEach { add(it) } }
                }
            },
        ),
    ) { "the fixture must parse" }

    private fun modelWindow(name: String?, utilization: Int?): JsonObject = buildJsonObject {
        if (name != null) put("display_name", name)
        if (utilization != null) put("utilization", utilization)
        put("resets_at", "2026-08-14T00:00:00+00:00")
    }

    @Test
    fun `a model_scoped window is parsed from the array and titled by the server's own name`() {
        val report = withModelScoped(modelWindow("Fable", 42))
        val (key, window) = report.windows.single { it.first.startsWith(MODEL_SCOPED_KEY_PREFIX) }
        assertEquals("${MODEL_SCOPED_KEY_PREFIX}Fable", key)
        assertEquals(42.0, window.utilization)
        assertEquals("Fable", window.title(key))
    }

    @Test
    fun `a keyed window keeps titling itself from its key`() {
        assertEquals("Current session", UsageWindow(utilization = 5.0).title("five_hour"))
        assertEquals("Opus", UsageWindow(utilization = 5.0).title("seven_day_opus"))
    }

    @Test
    fun `a model_scoped entry that duplicates a keyed window is dropped`() {
        val payload = buildJsonObject {
            put("rate_limits_available", true)
            putJsonObject("rate_limits") {
                putJsonObject("seven_day_opus") { put("utilization", 20) }
                putJsonArray("model_scoped") {
                    addJsonObject {
                        put("display_name", "opus")
                        put("utilization", 20)
                    }
                    addJsonObject {
                        put("display_name", "Fable")
                        put("utilization", 7)
                    }
                }
            }
        }
        val report = requireNotNull(parseUsageReport(payload))
        assertEquals(listOf("seven_day_opus", "${MODEL_SCOPED_KEY_PREFIX}Fable"), report.windows.map { it.first })
    }

    @Test
    fun `a model_scoped entry with no name or no figure is skipped`() {
        val report = withModelScoped(
            modelWindow(null, 30),
            modelWindow("  ", 30),
            modelWindow("Fable", null),
            modelWindow("Fable", 55),
        )
        assertEquals(listOf("five_hour", "${MODEL_SCOPED_KEY_PREFIX}Fable"), report.windows.map { it.first })
        assertEquals(55.0, report.windows.last().second.utilization)
    }

    @Test
    fun `model_scoped windows sort after the known ones`() {
        val report = withModelScoped(modelWindow("Fable", 3), modelWindow("Haiku", 1))
        assertEquals(
            listOf("five_hour", "${MODEL_SCOPED_KEY_PREFIX}Fable", "${MODEL_SCOPED_KEY_PREFIX}Haiku"),
            report.windows.map { it.first },
        )
    }

    @Test
    fun `model_scoped is never itself a window`() {
        assertTrue(withModelScoped(modelWindow("Fable", 3)).windows.none { it.first == "model_scoped" })
    }

    @Test
    fun `the hidden-window rule is public so both ingestion paths can apply it`() {
        assertTrue(isHiddenUsageWindow("nimbus_quill"))
        assertTrue(!isHiddenUsageWindow("five_hour"))
        assertTrue(!isHiddenUsageWindow(null))
    }

    private fun withRawLimits(vararg entries: JsonObject): UsageReport = requireNotNull(
        parseUsageReport(
            buildJsonObject {
                put("rate_limits_available", true)
                putJsonObject("rate_limits") {
                    putJsonObject("five_hour") { put("utilization", 13) }
                    putJsonArray("limits") { entries.forEach { add(it) } }
                }
            },
        ),
    ) { "the fixture must parse" }

    private fun rawLimit(
        model: String?,
        percent: Int?,
        kind: String = "weekly_scoped",
        resetsAt: JsonElement = JsonPrimitive(1_786_000_000L),
    ): JsonObject = buildJsonObject {
        put("kind", kind)
        if (model != null) {
            putJsonObject("scope") { putJsonObject("model") { put("display_name", model) } }
        }
        if (percent != null) put("percent", percent)
        put("resets_at", resetsAt)
    }

    @Test
    fun `a per-model window is projected from the raw limits array when model_scoped is absent`() {
        val report = withRawLimits(rawLimit("Fable", 71))
        val (key, window) = report.windows.single { it.first.startsWith(MODEL_SCOPED_KEY_PREFIX) }
        assertEquals("${MODEL_SCOPED_KEY_PREFIX}Fable", key)
        assertEquals(71.0, window.utilization)
        assertEquals("Fable", window.title(key))
    }

    @Test
    fun `epoch-seconds resets_at is normalised instead of dropping the window`() {
        val window = withRawLimits(rawLimit("Fable", 5)).windows.single {
            it.first.startsWith(MODEL_SCOPED_KEY_PREFIX)
        }.second
        assertEquals("2026-08-06T07:06:40Z", window.resetsAt)
        val iso = "2026-08-14T00:00:00Z"
        val asString = withRawLimits(rawLimit("Fable", 5, resetsAt = JsonPrimitive(iso))).windows.single {
            it.first.startsWith(MODEL_SCOPED_KEY_PREFIX)
        }.second
        assertEquals(iso, asString.resetsAt)
    }

    @Test
    fun `raw entries that are not a per-model weekly window are skipped`() {
        val report = withRawLimits(
            rawLimit("Fable", 8, kind = "five_hour"),
            rawLimit(null, 8),
            rawLimit("Nameless", null),
        )
        assertTrue(report.windows.none { it.first.startsWith(MODEL_SCOPED_KEY_PREFIX) })
    }

    @Test
    fun `a raw entry is dropped when the same model already has a window`() {
        val payload = buildJsonObject {
            put("rate_limits_available", true)
            putJsonObject("rate_limits") {
                putJsonObject("seven_day_opus") { put("utilization", 20) }
                putJsonArray("model_scoped") { add(modelWindow("Fable", 7)) }
                putJsonArray("limits") {
                    add(rawLimit("Opus", 20))
                    add(rawLimit("Fable", 99))
                    add(rawLimit("Haiku", 4))
                }
            }
        }
        val report = requireNotNull(parseUsageReport(payload))
        assertEquals(
            listOf("seven_day_opus", "${MODEL_SCOPED_KEY_PREFIX}Fable", "${MODEL_SCOPED_KEY_PREFIX}Haiku"),
            report.windows.map { it.first },
        )
        assertEquals(7.0, report.windows.first { it.first.endsWith("Fable") }.second.utilization)
    }

    @Test
    fun `the raw limits array is never itself a window`() {
        assertTrue(withRawLimits(rawLimit("Fable", 3)).windows.none { it.first == "limits" })
    }

    @Test
    fun `a window missing from the new report is carried forward`() {
        val previous = withRawLimits(rawLimit("Fable", 71))
        val seeded = requireNotNull(
            parseUsageReport(
                buildJsonObject {
                    put("rate_limits_available", true)
                    putJsonObject("rate_limits") {
                        putJsonObject("five_hour") { put("utilization", 20) }
                        putJsonObject("seven_day") { put("utilization", 9) }
                    }
                },
            ),
        )
        val merged = seeded.mergedOver(previous)
        assertEquals(
            listOf("five_hour", "seven_day", "${MODEL_SCOPED_KEY_PREFIX}Fable"),
            merged.windows.map { it.first },
        )
        assertEquals(20.0, merged.windows.first { it.first == "five_hour" }.second.utilization)
        assertEquals(71.0, merged.windows.first { it.first.endsWith("Fable") }.second.utilization)
    }

    @Test
    fun `a window the new report does mention is never overwritten by the old one`() {
        val previous = withRawLimits(rawLimit("Fable", 71))
        val fresh = withRawLimits(rawLimit("Fable", 4))
        assertEquals(
            4.0,
            fresh.mergedOver(previous).windows.first { it.first.endsWith("Fable") }.second.utilization,
        )
        assertEquals(previous.windows.size, fresh.mergedOver(previous).windows.size)
    }

    @Test
    fun `merging over nothing is the report itself`() {
        val report = withRawLimits(rawLimit("Fable", 71))
        assertEquals(report, report.mergedOver(null))
        assertEquals(report, report.mergedOver(UsageReport()))
    }

    @Test
    fun `the extra-credit balance is not carried forward`() {
        val previous = requireNotNull(
            parseUsageReport(
                buildJsonObject {
                    put("rate_limits_available", true)
                    putJsonObject("rate_limits") {
                        putJsonObject("five_hour") { put("utilization", 3) }
                        putJsonObject("extra_usage") {
                            put("is_enabled", true)
                            put("used_credits", 12)
                        }
                    }
                },
            ),
        )
        assertNotNull(previous.extra)
        assertNull(withRawLimits(rawLimit("Fable", 5)).mergedOver(previous).extra)
    }

    @Test
    fun `an event-sourced window converts its reset time to the shape the report already uses`() {
        assertEquals("2026-08-13T16:59:59Z", RateLimitInfo(resetsAt = 1_786_640_399L).resetsAtIso())
        assertNull(RateLimitInfo().resetsAtIso())
    }

    @Test
    fun `a window at one percent is one percent, not a hundred`() {
        assertEquals(1, UsageWindow(utilization = 1.0).utilizationPercent())
        assertEquals(1, UsageWindow(utilization = 0.9).utilizationPercent())
        assertEquals(0, UsageWindow(utilization = 0.0).utilizationPercent())
    }

    @Test
    fun `the get_usage scale is a percentage and is passed through`() {
        assertEquals(8, UsageWindow(utilization = 8.0).utilizationPercent())
        assertEquals(92, UsageWindow(utilization = 92.0).utilizationPercent())
        assertEquals(100, UsageWindow(utilization = 100.0).utilizationPercent())
    }

    @Test
    fun `an absent utilization stays absent, and nonsense is clamped`() {
        assertNull(UsageWindow().utilizationPercent())
        assertEquals(100, UsageWindow(utilization = 250.0).utilizationPercent())
        assertEquals(0, UsageWindow(utilization = -5.0).utilizationPercent())
    }

    @Test
    fun `a window whose reset has passed reads zero`() {
        val report = UsageReport(
            windows = listOf(
                "five_hour" to UsageWindow(utilization = 100.0, resetsAt = "2026-08-17T17:00:00.000Z"),
                "seven_day" to UsageWindow(utilization = 68.0, resetsAt = "2026-08-20T17:00:00.000Z"),
            ),
        )

        val settled = report.afterResets(Instant.parse("2026-08-17T17:00:05Z").toEpochMilli())

        assertEquals(0.0, settled.windows.first { it.first == "five_hour" }.second.utilization)
        assertEquals(68.0, settled.windows.first { it.first == "seven_day" }.second.utilization)
    }

    @Test
    fun `both spellings of resets_at are understood`() {
        val zulu = UsageWindow(utilization = 90.0, resetsAt = "2026-08-17T17:00:00.000Z")
        val offset = UsageWindow(utilization = 90.0, resetsAt = "2026-08-17T17:00:01.137340+00:00")
        val after = Instant.parse("2026-08-17T18:00:00Z").toEpochMilli()

        assertTrue(zulu.hasReset(after))
        assertTrue(offset.hasReset(after))
    }

    @Test
    fun `an unreadable or absent reset time changes nothing`() {
        val now = Instant.parse("2026-08-17T18:00:00Z").toEpochMilli()
        val report = UsageReport(
            windows = listOf(
                "odd" to UsageWindow(utilization = 42.0, resetsAt = "whenever"),
                "none" to UsageWindow(utilization = 43.0),
            ),
        )

        assertSame(report, report.afterResets(now))
        assertFalse(UsageWindow(resetsAt = "whenever").hasReset(now))
        assertFalse(UsageWindow().hasReset(now))
    }

    @Test
    fun `a window still inside its window is left alone`() {
        val window = UsageWindow(utilization = 30.0, resetsAt = "2026-08-17T17:00:00.000Z")

        assertFalse(window.hasReset(Instant.parse("2026-08-17T16:59:00Z").toEpochMilli()))
    }
}
