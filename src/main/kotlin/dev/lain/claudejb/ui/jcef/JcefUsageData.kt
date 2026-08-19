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

internal object JcefUsageData {

    fun usageJson(session: ClaudeSession, report: UsageReport?): JsonObject? {
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

    private fun extraUsageJson(extra: ExtraUsage): JsonObject = buildJsonObject {
        put("enabled", extra.isEnabled)
        put("spent", extra.usedCredits?.let { it / TEN.pow(extra.decimalPlaces) })
        put("limit", extra.monthlyLimit)
        put("currency", extra.currency)
        put("pct", extra.utilization)
        put("limitReached", extra.spendLimitReached)
    }

    private const val TEN = 10.0

    private fun Double.pow(exp: Int): Double = Math.pow(this, exp.toDouble())
}
