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

    @Test
    fun `nimbus_quill is dropped, and every other unknown window is still shown`() {
        // The server emits a window key that exists in no binary and no SDK type, so nothing can say what it
        // meters; it rendered as "Nimbus quill 0.0%". Hidden BY NAME — the general rule (an unknown window is
        // still a limit, so label it rather than hide it) must survive intact, or the next real limit
        // Anthropic ships disappears silently.
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

    // --- model_scoped: the per-model weekly windows, the only place "Fable" is ever reported ---

    /** A payload whose `rate_limits` carries the `model_scoped` ARRAY alongside the ordinary window objects. */
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
        // THE FEATURE: Fable usage is reported HERE and nowhere else. `model_scoped` is a JSON array, so the
        // `value as? JsonObject` walk over `rate_limits` silently dropped every entry — the window did not
        // render wrongly, it did not exist.
        val report = withModelScoped(modelWindow("Fable", 42))
        val (key, window) = report.windows.single { it.first.startsWith(MODEL_SCOPED_KEY_PREFIX) }
        assertEquals("${MODEL_SCOPED_KEY_PREFIX}Fable", key)
        assertEquals(42.0, window.utilization)
        // Its key is synthetic, so the label can only come from `display_name`; deriving it from the key would
        // read "Model scoped:fable".
        assertEquals("Fable", window.title(key))
    }

    @Test
    fun `a keyed window keeps titling itself from its key`() {
        // `title()` is the ONE place every surface labels a window, so it has to keep answering correctly for
        // the windows that have no display_name — which is all of them except the model-scoped ones.
        assertEquals("Current session", UsageWindow(utilization = 5.0).title("five_hour"))
        assertEquals("Opus", UsageWindow(utilization = 5.0).title("seven_day_opus"))
    }

    @Test
    fun `a model_scoped entry that duplicates a keyed window is dropped`() {
        // The SDK calls these additive and the server picks which models qualify, so a "Opus" bucket next to
        // the first-class seven_day_opus is expected rather than exceptional. Two bars both reading "Opus" is
        // worse than one; the keyed window wins, being the one whose meaning the plugin actually knows.
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
        // Its key IS its name, so a blank one has neither identity nor title: it would render as an anonymous
        // bar, the same unanswerable row nimbus_quill is hidden for. A named entry with no utilization has
        // nothing to draw either — and, as everywhere else here, absent is not zero.
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
        // Same rule the unknown top-level keys follow: the windows the plugin understands lead, the additive
        // per-model buckets trail, and `sortedBy` is stable so the server's own order is preserved among them.
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
        // The report path filters in parseUsageReport; the rate_limit_event path filters in
        // ClaudeSession.onRateLimit. Filtering only one left "Nimbus quill 0.0%" on screen anyway.
        assertTrue(isHiddenUsageWindow("nimbus_quill"))
        assertTrue(!isHiddenUsageWindow("five_hour"))
        assertTrue(!isHiddenUsageWindow(null))
    }

    // --- the raw `limits[]` array: where Fable ACTUALLY comes from in a plugin session ---

    /** A payload carrying only the raw `rate_limits.limits` array, with no `model_scoped` key at all. */
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

    /** One raw entry, in the binary's own shape: `kind`/`scope.model.display_name`/`percent`/`resets_at`. */
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
        // THE BUG THE USER SAW: the plugin showed "Current session" and "All models" and never Fable, while
        // the same account's interactive /usage listed it. `model_scoped` is not a field the server always
        // sends — the binary SYNTHESISES it, and only when its `tengu_usage_overage_included_models` gate is
        // non-empty (`IUt` returns [] for an empty gate, and the key is spliced in only when the projection
        // yields entries). In a --print session it was empty, so the key never arrived. The raw array it
        // projects FROM does arrive, and the binary's own /usage formatter reads it off this same payload.
        val report = withRawLimits(rawLimit("Fable", 71))
        val (key, window) = report.windows.single { it.first.startsWith(MODEL_SCOPED_KEY_PREFIX) }
        assertEquals("${MODEL_SCOPED_KEY_PREFIX}Fable", key)
        assertEquals(71.0, window.utilization)
        assertEquals("Fable", window.title(key))
    }

    @Test
    fun `epoch-seconds resets_at is normalised instead of dropping the window`() {
        // The raw entries carry `resets_at` as a NUMBER as often as a string, and `UsageWindow.resetsAt` is a
        // String — decoding one straight would fail and take the whole window with it, silently.
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
        // The array holds every limit the account has, most of them the same windows already keyed by name.
        // Only `weekly_scoped` entries that actually name a model become a bar; the rest would duplicate a
        // window that is already on screen, or be an anonymous one.
        val report = withRawLimits(
            rawLimit("Fable", 8, kind = "five_hour"),
            rawLimit(null, 8),
            rawLimit("Nameless", null),
        )
        assertTrue(report.windows.none { it.first.startsWith(MODEL_SCOPED_KEY_PREFIX) })
    }

    @Test
    fun `a raw entry is dropped when the same model already has a window`() {
        // Both dedup rules on one payload: against a keyed window (seven_day_opus vs an "Opus" raw entry) and
        // against the binary's own projection, which wins because it IS the server's projection. Otherwise
        // enabling the gate would double every bar.
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
        // The surviving Fable is the projected one (7), not the raw duplicate (99).
        assertEquals(7.0, report.windows.first { it.first.endsWith("Fable") }.second.utilization)
    }

    @Test
    fun `the raw limits array is never itself a window`() {
        assertTrue(withRawLimits(rawLimit("Fable", 3)).windows.none { it.first == "limits" })
    }

    // --- mergedOver: a refresh that omits a window is not a claim that the window is gone ---

    @Test
    fun `a window missing from the new report is carried forward`() {
        // THE BUG THE USER SAW NEXT: the Fable bar blinked out and back every few polls. The binary's usage
        // fetch falls back to `seedUtilization()` on a timeout/429/fieldless body, and that object is rebuilt
        // from the rate-limit RESPONSE HEADERS — it can only ever carry five_hour and seven_day. Downstream it
        // is flagged "seeded" and then treated exactly like a full reply, so the omission is invisible.
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
        // The fresh reading wins where the reply HAS one; only the untold window keeps its last value.
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
        // Unlike a window, `extra` being null already means "this plan has no extra-credit balance" as often as
        // it means "this reply did not say" — carrying it would keep a balance on screen after it is turned off.
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
        // The composer readout and the dashboard both render a countdown, and a window can reach them from
        // either path. One conversion on the model, so an event-sourced window and a report-sourced one are
        // interchangeable to every surface instead of ISO in one place and epoch seconds in the other.
        assertEquals("2026-08-13T16:59:59Z", RateLimitInfo(resetsAt = 1_786_640_399L).resetsAtIso())
        assertNull(RateLimitInfo().resetsAtIso())
    }

    // --- UsageWindow.utilizationPercent: the scale, and the bug that came from guessing it ---

    @Test
    fun `a window at one percent is one percent, not a hundred`() {
        // THE BUG, as a test. `ClaudeSession` held a private copy of a "the wire sends both 0..100 and 0..1,
        // accept either" heuristic: any value <= 1.0 was multiplied by 100. So a window at a genuine 1% came
        // out as 100%, crossed the 85% threshold and raised an IDE notification saying the plan was spent —
        // at the moment the user had spent almost none of it, i.e. right after a window reset.
        assertEquals(1, UsageWindow(utilization = 1.0).utilizationPercent())
        assertEquals(1, UsageWindow(utilization = 0.9).utilizationPercent())
        assertEquals(0, UsageWindow(utilization = 0.0).utilizationPercent())
    }

    @Test
    fun `the get_usage scale is a percentage and is passed through`() {
        // sdk.d.ts on every window: "Percentage of the window used, 0-100". The live fixture above says the
        // same thing out loud — it carries 8 and 67.
        assertEquals(8, UsageWindow(utilization = 8.0).utilizationPercent())
        assertEquals(92, UsageWindow(utilization = 92.0).utilizationPercent())
        assertEquals(100, UsageWindow(utilization = 100.0).utilizationPercent())
    }

    @Test
    fun `an absent utilization stays absent, and nonsense is clamped`() {
        // null must not become 0: "unknown" and "none used" are different claims, and only one of them is
        // safe to skip a quota warning on. The clamp is belt-and-braces against a wire value out of range.
        assertNull(UsageWindow().utilizationPercent())
        assertEquals(100, UsageWindow(utilization = 250.0).utilizationPercent())
        assertEquals(0, UsageWindow(utilization = -5.0).utilizationPercent())
    }
}
