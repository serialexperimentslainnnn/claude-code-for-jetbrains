package dev.lain.claudejb.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private val USAGE_WINDOW_ORDER = listOf("five_hour", "seven_day", "seven_day_opus", "seven_day_sonnet")

private val HIDDEN_WINDOWS = setOf("nimbus_quill")

fun isHiddenUsageWindow(window: String?): Boolean = window in HIDDEN_WINDOWS

const val MODEL_SCOPED_KEY_PREFIX = "model_scoped:"

fun parseUsageReport(payload: JsonObject?): UsageReport? {
    payload ?: return null
    val limits = payload["rate_limits"] as? JsonObject
    val extra = (limits?.get("extra_usage") as? JsonObject)?.let {
        runCatching { ClaudeJson.decodeFromJsonElement(ExtraUsage.serializer(), it) }.getOrNull()
    }
    val keyed = limits.orEmpty().mapNotNull { (key, value) ->
        if (key == "extra_usage" || key == MODEL_SCOPED) return@mapNotNull null
        if (key in HIDDEN_WINDOWS) return@mapNotNull null
        val obj = value as? JsonObject ?: return@mapNotNull null
        decodeWindow(obj)?.let { key to it }
    }
    val windows = sortUsageWindows(keyed + perModelWindows(limits, keyed))
    val report = UsageReport(
        subscriptionType = payload.str("subscription_type"),
        available = (payload["rate_limits_available"] as? JsonPrimitive)?.booleanOrNull ?: false,
        windows = windows,
        extra = extra?.takeIf { it.isEnabled },
    )
    return report.takeUnless { it.isEmpty }
}

private fun sortUsageWindows(windows: List<Pair<String, UsageWindow>>): List<Pair<String, UsageWindow>> =
    windows.sortedBy { (key, _) ->
        USAGE_WINDOW_ORDER.indexOf(key).takeIf { it >= 0 } ?: USAGE_WINDOW_ORDER.size
    }

fun UsageReport.mergedOver(previous: UsageReport?): UsageReport {
    val earlier = previous?.windows.orEmpty()
    if (earlier.isEmpty()) return this
    val present = windows.mapTo(mutableSetOf()) { it.first }
    val carried = earlier.filterNot { (key, _) -> key in present }
    return if (carried.isEmpty()) this else copy(windows = sortUsageWindows(windows + carried))
}

fun UsageReport.afterResets(nowMillis: Long): UsageReport {
    val settled = windows.map { (key, window) ->
        if (window.hasReset(nowMillis)) key to window.copy(utilization = 0.0) else key to window
    }
    return if (settled == windows) this else copy(windows = settled)
}

private const val MODEL_SCOPED = "model_scoped"

private const val RAW_LIMITS = "limits"

private const val WEEKLY_SCOPED = "weekly_scoped"

private fun decodeWindow(obj: JsonObject): UsageWindow? =
    runCatching { ClaudeJson.decodeFromJsonElement(UsageWindow.serializer(), obj) }
        .getOrNull()
        ?.takeIf { it.utilization != null || it.usedDollars != null }

private fun perModelWindows(
    limits: JsonObject?,
    alreadyKeyed: List<Pair<String, UsageWindow>>,
): List<Pair<String, UsageWindow>> {
    val taken = alreadyKeyed.mapTo(mutableSetOf()) { (key, w) -> w.title(key).lowercase() }
    val candidates = modelScopedEntries(limits) + rawWeeklyScopedEntries(limits)
    return candidates.mapNotNull { (name, window) ->
        if (!taken.add(name.lowercase())) return@mapNotNull null
        "$MODEL_SCOPED_KEY_PREFIX$name" to window
    }
}

private fun modelScopedEntries(limits: JsonObject?): List<Pair<String, UsageWindow>> {
    val entries = limits?.get(MODEL_SCOPED) as? JsonArray ?: return emptyList()
    return entries.mapNotNull { element ->
        val window = decodeWindow(element as? JsonObject ?: return@mapNotNull null) ?: return@mapNotNull null
        val name = window.displayName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        name to window
    }
}

private fun rawWeeklyScopedEntries(limits: JsonObject?): List<Pair<String, UsageWindow>> {
    val entries = limits?.get(RAW_LIMITS) as? JsonArray ?: return emptyList()
    return entries.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        if (obj.str("kind") != WEEKLY_SCOPED) return@mapNotNull null
        val model = (obj["scope"] as? JsonObject)?.get("model") as? JsonObject
        val name = model?.str("display_name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val percent = (obj["percent"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
            ?: return@mapNotNull null
        name to UsageWindow(utilization = percent, resetsAt = isoResetsAt(obj["resets_at"]), displayName = name)
    }
}

private fun isoResetsAt(value: kotlinx.serialization.json.JsonElement?): String? {
    val raw = (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val epochSeconds = raw.toLongOrNull() ?: return raw
    return runCatching { java.time.Instant.ofEpochSecond(epochSeconds).toString() }.getOrNull()
}

private fun JsonObject?.orEmpty(): Map<String, kotlinx.serialization.json.JsonElement> = this ?: emptyMap()
