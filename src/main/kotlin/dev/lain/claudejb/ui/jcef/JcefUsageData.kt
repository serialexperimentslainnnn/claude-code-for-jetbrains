package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.protocol.ExtraUsage
import dev.lain.claudejb.protocol.RateLimitInfo
import dev.lain.claudejb.protocol.UsageReport
import dev.lain.claudejb.session.ClaudeSession
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The dashboard's plan-limits card. One card of [JcefSessionData]'s payload; see there for the whole shape. */
internal object JcefUsageData {

    /**
     * `{ plan, windows:[{ key, label, pct, resetsAt, exhausted }], extra:{…} }`, or null when nothing is known.
     *
     * Two sources, deliberately: the `get_usage` [report] is authoritative because one round-trip returns
     * EVERY window, while [ClaudeSession.rateLimits] only knows about a window once an event has moved it. The
     * events are the fallback so the panel still shows something before the first poll lands, and the nudge
     * that it is worth polling again.
     *
     * `pct` may be null — a window can be known without a percentage (the binary only sends `utilization` when
     * the API returns it). The frontend renders that as "—" and an empty bar rather than as 0%, because
     * "unknown" and "none used" are different claims and a bar cannot show both.
     */
    fun usageJson(session: ClaudeSession, report: UsageReport?): JsonObject? {
        // EXPERIMENT (Lain's comma test): carry the decimals — do NOT round to Int — so we can see whether the
        // frontend renders a fractional percentage with a comma (locale formatting in play) or a dot.
        val fromReport = report?.windows?.map { (key, w) ->
            Window(key, w.title(key), w.utilization, w.resetsAt, exhausted = false)
        }.orEmpty()
        val fromEvents = session.rateLimits
            .filterKeys { key -> fromReport.none { it.key == key } }
            .map { (key, info) ->
                val pct = info.utilization?.let { it * 100 }
                Window(key, RateLimitInfo.windowTitleFor(key), pct, info.resetsAtIso(), info.isExhausted)
            }
        val windows = fromReport + fromEvents
        if (windows.isEmpty() && report?.extra == null) return null
        return buildJsonObject {
            put("plan", report?.subscriptionType ?: session.account?.subscriptionType?.ifBlank { null })
            put(
                "windows",
                buildJsonArray {
                    windows.forEach { w ->
                        addJsonObject {
                            put("key", w.key)
                            put("label", w.label)
                            put("pct", w.pct)
                            put("resetsAt", w.resetsAt)
                            put("exhausted", w.exhausted)
                        }
                    }
                },
            )
            put("extra", report?.extra?.let { extraUsageJson(it) } ?: JsonNull)
        }
    }

    private data class Window(
        val key: String,
        val label: String,
        val pct: Double?,
        val resetsAt: String?,
        val exhausted: Boolean,
    )

    /** The pay-as-you-go balance. Credits are minor units (`decimal_places`), not whole currency. */
    private fun extraUsageJson(extra: ExtraUsage): JsonObject = buildJsonObject {
        put("enabled", extra.isEnabled)
        put("spent", extra.usedCredits?.let { it / TEN.pow(extra.decimalPlaces) })
        put("limit", extra.monthlyLimit)
        put("currency", extra.currency)
        put("pct", extra.utilization) // EXPERIMENT: raw, un-rounded, like the windows — so the decimal shows
        put("limitReached", extra.spendLimitReached)
    }

    // NB the `local_agent` filter lives in BackgroundTaskRegistry now: it was duplicated here and in the tab
    // rows, which is two places for one rule about what counts as a background task.

    private const val TEN = 10.0

    private fun Double.pow(exp: Int): Double = Math.pow(this, exp.toDouble())
}
