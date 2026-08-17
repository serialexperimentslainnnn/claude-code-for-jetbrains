package dev.lain.claudejb.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Windows first in this order; anything the binary adds later sorts after, in the order it sent them. */
private val USAGE_WINDOW_ORDER = listOf("five_hour", "seven_day", "seven_day_opus", "seven_day_sonnet")

/**
 * Windows dropped before they reach any surface — dashboard bar, composer dot or quota warning.
 *
 * **This is a deliberate exception to the rule right below it**, which is that an unknown window is still a
 * limit the user is subject to and gets shown with a derived label rather than hidden. `nimbus_quill` is a
 * key the claude.ai usage endpoint emits and the CLI relays untouched — it appears in no version of the
 * binary (grepped: zero occurrences) and in no SDK type, so nothing here can say what it meters. It rendered
 * as "Nimbus quill 0.0%", which is a row that asks a question and answers none.
 *
 * **Temporary, and keyed by name so it stays cheap to undo**: the moment that window means something to a
 * user, delete the entry and it comes back with everything else it already has — ordering, label, bar.
 * Deliberately NOT generalised into "hide unknown windows", which would silently swallow the next real limit
 * Anthropic ships.
 *
 * Applied on BOTH paths that feed a window to the UI — the `get_usage` report here and the `rate_limit_event`
 * stream in `ClaudeSession.onRateLimit`, which is why it is public. Filtering only the report left the row on
 * screen anyway, arriving by the other door.
 */
private val HIDDEN_WINDOWS = setOf("nimbus_quill")

/** Whether [window] is one of the [HIDDEN_WINDOWS] the UI never shows. */
fun isHiddenUsageWindow(window: String?): Boolean = window in HIDDEN_WINDOWS

/**
 * Prefix of the synthetic key given to a `model_scoped` window, whose real identity is its `display_name`.
 *
 * Unlike every other window, these arrive in a JSON **array** with no key of their own, so one is made here.
 * It has to be stable across refreshes — the "announce a quota crossing once" record is kept per key — and it
 * has to be namespaced, so a bucket the server one day labels "Overage" cannot collide with the top-level
 * window of that name.
 */
const val MODEL_SCOPED_KEY_PREFIX = "model_scoped:"

/**
 * Parses a `get_usage` reply. Returns null when the payload is absent or carries nothing worth showing.
 *
 * `rate_limits` is a heterogeneous object — window entries, explicit nulls for untouched windows, the
 * `model_scoped` ARRAY, and `extra_usage` with an entirely different shape — so it is walked by hand rather
 * than deserialized whole. A window that fails to decode is skipped, never thrown on: this feeds a dashboard,
 * and one unrecognised key from a newer binary must not blank the whole panel.
 */
fun parseUsageReport(payload: JsonObject?): UsageReport? {
    payload ?: return null
    val limits = payload["rate_limits"] as? JsonObject
    val extra = (limits?.get("extra_usage") as? JsonObject)?.let {
        runCatching { ClaudeJson.decodeFromJsonElement(ExtraUsage.serializer(), it) }.getOrNull()
    }
    val keyed = limits.orEmpty().mapNotNull { (key, value) ->
        if (key == "extra_usage" || key == MODEL_SCOPED) return@mapNotNull null
        if (key in HIDDEN_WINDOWS) return@mapNotNull null
        val obj = value as? JsonObject ?: return@mapNotNull null // null = window exists but untouched
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

/** Known windows lead, in [USAGE_WINDOW_ORDER]; anything else trails in the order it arrived (stable sort). */
private fun sortUsageWindows(windows: List<Pair<String, UsageWindow>>): List<Pair<String, UsageWindow>> =
    windows.sortedBy { (key, _) ->
        USAGE_WINDOW_ORDER.indexOf(key).takeIf { it >= 0 } ?: USAGE_WINDOW_ORDER.size
    }

/**
 * This report over a [previous] one: fresh windows win, windows it does not mention are carried forward.
 *
 * **A refresh that omits a window is not a claim that the window is gone**, and treating it as one made the
 * per-model bars blink in and out every poll. The cause is in the binary and is structural: `loadPlanRateLimits`
 * fetches `/api/oauth/usage` with a 5 s timeout, and on a timeout, a 429 or a fieldless body it falls back to
 * `seedUtilization()` — an object rebuilt from the rate-limit *response headers*, which by construction can
 * only carry `five_hour` and `seven_day`. The reply is then flagged `status:"seeded"`, and `collectUsageData`
 * accepts `"ok"` and `"seeded"` identically, so a seeded refresh is indistinguishable downstream from a full
 * one that genuinely has no per-model window. Verified against `claude` 2.1.223.
 *
 * So the merge is by window key, over the whole set rather than only the per-model ones: `seven_day_opus` and
 * `seven_day_sonnet` are absent from a seeded object for exactly the same reason and would flicker exactly the
 * same way. A carried-forward window keeps the last figure that was actually reported for it, and the next
 * unseeded refresh overwrites it — within one session, which is the only lifetime this holds for.
 *
 * [UsageReport.extra] is deliberately NOT carried: `null` there already means "the plan has no extra-credit
 * balance" as often as it means "this reply did not say", and inventing the distinction would keep a stale
 * balance on screen after the user turns the feature off.
 */
fun UsageReport.mergedOver(previous: UsageReport?): UsageReport {
    val earlier = previous?.windows.orEmpty()
    if (earlier.isEmpty()) return this
    val present = windows.mapTo(mutableSetOf()) { it.first }
    val carried = earlier.filterNot { (key, _) -> key in present }
    return if (carried.isEmpty()) this else copy(windows = sortUsageWindows(windows + carried))
}

/**
 * Zeroes every window whose reset moment has already passed — **the fix for a plan that reads 100% spent
 * when it is not**.
 *
 * The binary keeps answering `get_usage` with the utilization it last computed and a `resets_at` that is now
 * in the PAST. Nothing in that reply is a lie: the number was true of a window that has since rolled over,
 * and the binary refreshes it when it feels like it — in practice, when it is restarted. So the composer sat
 * at "100% · Reset time: soon" for as long as the user left the IDE open, which is the most alarming thing
 * this plugin can put on screen and it was wrong.
 *
 * A window past its reset has a KNOWN utilization, and it is zero: that is what resetting means. Reporting it
 * as such is not a guess — the guess was carrying the old number across the boundary. Applied once, where the
 * reply is accepted, so the four things that read `windows` cannot disagree about it.
 *
 * [nowMillis] is a parameter for the reason [dev.lain.claudejb.session.WorkloadWindow] gives: a rule that
 * reads its own clock cannot be reasoned about from outside it.
 *
 * The `resets_at` timestamps arrive in two spellings — `2026-08-17T17:00:00.000Z` and
 * `2026-08-17T17:00:01.137340+00:00`, both observed in one afternoon's replies — so both are parsed, and
 * anything else leaves the window exactly as it came. An unreadable timestamp is not evidence of a reset.
 */
fun UsageReport.afterResets(nowMillis: Long): UsageReport {
    val settled = windows.map { (key, window) ->
        if (window.hasReset(nowMillis)) key to window.copy(utilization = 0.0) else key to window
    }
    return if (settled == windows) this else copy(windows = settled)
}

private const val MODEL_SCOPED = "model_scoped"

/** The raw per-limit array the binary's own per-model projection reads from, relayed to us untouched. */
private const val RAW_LIMITS = "limits"

/** The `kind` that marks a raw entry as a per-model weekly window. */
private const val WEEKLY_SCOPED = "weekly_scoped"

private fun decodeWindow(obj: JsonObject): UsageWindow? =
    runCatching { ClaudeJson.decodeFromJsonElement(UsageWindow.serializer(), obj) }
        .getOrNull()
        // A window with neither a percentage nor a dollar figure has nothing to draw. Deliberately NOT the
        // same as absent: the binary sends explicit nulls for limits that exist but have not been touched.
        ?.takeIf { it.utilization != null || it.usedDollars != null }

/**
 * The per-model weekly windows, the only ones that name the model they meter — "Fable" is reported here and
 * nowhere else, so without this it simply does not exist for the user.
 *
 * **Two sources, and the second one is the load-bearing one.** The binary offers a ready-made `model_scoped`
 * array, but it emits it only when its own remote config says so: `LCn()` projects the windows through
 * `IUt(limits, jJe())`, where `jJe()` reads the `tengu_usage_overage_included_models` gate and `IUt` returns
 * an EMPTY list the moment that gate is empty — and `rate_limits` then carries no `model_scoped` key at all.
 * Verified against `claude` 2.1.223 (the projection, its gate, and the `i.length > 0` condition that decides
 * whether the key is spliced in), and confirmed live: the plugin's `--print` session logged `five_hour` and
 * `seven_day` and never a per-model window, while the same account's interactive `/usage` listed Fable.
 *
 * So the raw array the projection reads from — `rate_limits.limits[]`, which rides through untouched, as the
 * binary's own `/usage` formatter assumes when it calls `IUt(t.limits, …)` on this very payload — is walked
 * here as well: `kind == "weekly_scoped"` entries with a `scope.model.display_name`, exactly the filter the
 * binary applies, minus its allowlist. Dropping the allowlist is the point: it decides which models get
 * *overage* billing, not which limits a user is subject to, and a limit that meters you is worth showing
 * whether or not you can pay past it.
 *
 * `model_scoped` still wins where both carry a model, since it is the server's own projection; the raw array
 * fills in the rest. Overlap with `seven_day_opus`/`seven_day_sonnet` is expected rather than exceptional
 * (the SDK calls these windows *additive*), so an entry whose label already titles one of [alreadyKeyed] is
 * dropped — two bars reading "Opus" is worse than one, and the keyed window is the one whose meaning the
 * plugin knows. An entry with no name is skipped: its key is synthesised from that name, so a blank one has
 * neither identity nor title and would render as the same unanswerable bar `nimbus_quill` was hidden for.
 */
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

/** The binary's own projection, `rate_limits.model_scoped` — present only when its remote gate is set. */
private fun modelScopedEntries(limits: JsonObject?): List<Pair<String, UsageWindow>> {
    val entries = limits?.get(MODEL_SCOPED) as? JsonArray ?: return emptyList()
    return entries.mapNotNull { element ->
        val window = decodeWindow(element as? JsonObject ?: return@mapNotNull null) ?: return@mapNotNull null
        val name = window.displayName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        name to window
    }
}

/**
 * The same windows read from the raw `rate_limits.limits[]` array the binary projects from.
 *
 * Field names differ from every other window here and are taken from the binary, not guessed: the percentage
 * is `percent` (already 0–100 — its `/usage` formatter prints `Math.floor(utilization)%` straight from it) and
 * `resets_at` is epoch **seconds** as often as it is a string, which is why it is normalised rather than
 * handed to the deserializer: a numeric one would fail to decode and silently drop the whole window.
 */
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

/** `resets_at` as ISO-8601, converting the epoch-seconds form the raw `limits[]` entries use. */
private fun isoResetsAt(value: kotlinx.serialization.json.JsonElement?): String? {
    val raw = (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val epochSeconds = raw.toLongOrNull() ?: return raw
    return runCatching { java.time.Instant.ofEpochSecond(epochSeconds).toString() }.getOrNull()
}

private fun JsonObject?.orEmpty(): Map<String, kotlinx.serialization.json.JsonElement> = this ?: emptyMap()
