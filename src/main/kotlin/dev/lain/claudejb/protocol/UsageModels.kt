package dev.lain.claudejb.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// get_context_usage response (binary -> host): drives the context meter (/context).
// ---------------------------------------------------------------------------

@Serializable
data class ContextUsage(
    val totalTokens: Long = 0,
    val maxTokens: Long = 0,
    val percentage: Double = 0.0,
    val categories: List<ContextCategory> = emptyList(),
)

@Serializable
data class ContextCategory(
    val name: String = "",
    val tokens: Long = 0,
)

/**
 * `apiUsage` block of the `get_session_cost` control response: the binary's **authoritative cumulative**
 * token tally for the session (the same figures the Anthropic API returns). Preferred over locally-folded
 * counters for display, which can drift. Matches `SDKControlGetSessionCostResponse.apiUsage` in sdk.d.ts.
 */
@Serializable
data class SessionCostUsage(
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Long = 0,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Long = 0,
)

// ---------------------------------------------------------------------------
// rate_limit_event (binary -> host): subscription quota usage for claude.ai users.
// `utilization` is only present when the binary has it (typically near the limit).
// ---------------------------------------------------------------------------

@Serializable
data class RateLimitInfo(
    val status: String = "allowed", // allowed | allowed_warning | rejected
    val resetsAt: Long? = null, // epoch seconds when this window resets
    val rateLimitType: String? = null, // five_hour | seven_day | seven_day_opus/sonnet | overage
    val utilization: Double? = null, // FRACTION of quota used, 0..1 — see utilizationPercent()
    val overageStatus: String? = null,
    val isUsingOverage: Boolean = false,
    // Both of these ARE on the wire and were previously dropped — confirmed by capturing a live
    // rate_limit_event, which carried `overageResetsAt` and `overageInUse` alongside the fields above.
    // `overageResetsAt` is the only way to tell the user when overage billing rolls over.
    val overageResetsAt: Long? = null,
    val overageInUse: Boolean = false,
    val surpassedThreshold: Double? = null,
) {
    /** Clamped 0..100 percent, or null if the binary didn't report utilization. */
    fun utilizationPercent(): Int? =
        utilization?.let { Math.round(it * PERCENT).toInt().coerceIn(0, 100) }

    /**
     * [resetsAt] as ISO-8601, the shape `get_usage` already uses, so a window sourced from an EVENT and one
     * sourced from the REPORT are interchangeable to every surface that renders a countdown. The conversion
     * lives on the model because both UI builders need it and two copies would be two chances to drift.
     */
    fun resetsAtIso(): String? = resetsAt?.let { java.time.Instant.ofEpochSecond(it).toString() }

    val isExhausted: Boolean get() = status == "rejected"

    companion object {
        /** The event's fraction → percent. [UsageWindow] needs no such factor: it is already 0..100. */
        private const val PERCENT = 100

        /**
         * DESCRIPTIVE label for a window: what each bar measures. The ONE label — the composer's readout and
         * the dashboard's card both draw a labelled bar and both call this. There used to be a short form
         * ("5h", "7d") for a composer quota PILL, and it outlived the pill by a release.
         *
         * Unknown keys are title-cased rather than dropped. The binary keeps adding windows
         * (`seven_day_cowork`, `seven_day_omelette`, and several codenamed ones), and a window we cannot label
         * is still a window the user is being limited by — showing it as-is beats hiding it.
         */
        fun windowTitleFor(type: String?): String = when (type) {
            "five_hour" -> "Current session"
            "seven_day" -> "All models"
            "seven_day_opus" -> "Opus"
            "seven_day_sonnet" -> "Sonnet"
            "seven_day_oauth_apps" -> "OAuth apps"
            "overage" -> "Overage"
            null, "" -> "Quota"
            else -> type.removePrefix("seven_day_").replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }
}

/**
 * One usage window from the `get_usage` control reply, e.g. `rate_limits.five_hour`.
 *
 * Note the shape difference from [RateLimitInfo], which models the *event*: here `resets_at` is an ISO-8601
 * string, not epoch seconds, and the dollar fields exist for plans that are billed rather than throttled.
 */
@Serializable
data class UsageWindow(
    val utilization: Double? = null,
    @SerialName("resets_at") val resetsAt: String? = null,
    @SerialName("limit_dollars") val limitDollars: Double? = null,
    @SerialName("used_dollars") val usedDollars: Double? = null,
    @SerialName("remaining_dollars") val remainingDollars: Double? = null,
    // Only the `model_scoped` entries carry this. It is the SERVER's own label for the bucket ("Fable"), and
    // the only thing that names them: their key is synthesised here, so there is nothing to derive a title
    // from the way `seven_day_opus` derives "Opus".
    @SerialName("display_name") val displayName: String? = null,
) {
    /**
     * How this window is titled in the UI: the server's own label when it sent one, else the key's.
     *
     * Every surface (dashboard bar, composer dot, quota warning) goes through here, so a per-model window
     * cannot end up correctly labelled in one place and titled from its synthetic key in another.
     */
    fun title(key: String): String =
        displayName?.takeIf { it.isNotBlank() } ?: RateLimitInfo.windowTitleFor(key)

    /**
     * The window's percentage, 0..100, or null when the binary reported none.
     *
     * **THE SCALE IS ALREADY A PERCENTAGE HERE, and this is the whole point of the function existing.**
     * `sdk.d.ts` documents every `get_usage` window as *"Percentage of the window used, 0-100"* — unlike
     * [RateLimitInfo.utilization] on the *event*, which is a 0..1 fraction. The two really do differ, and
     * conflating them is not a rounding error but a factor of a hundred in either direction.
     *
     * There is deliberately **no "accept 0..1 too" heuristic**. It is undecidable at exactly 1.0, and it
     * decided wrong: a window at a genuine **1%** was read as a fraction and reported as **100%**, which
     * fired the 85% quota notification — telling the user their plan was spent at the moment they had spent
     * almost none of it. Reachable by every user at the start of every freshly reset window, and it survived
     * as a private copy in `ClaudeSession` after the same rule had been removed from the two display paths.
     * Hence one function, on the model, for every caller.
     */
    fun utilizationPercent(): Int? =
        utilization?.let { Math.round(it).toInt().coerceIn(0, PERCENT) }

    // NOT a private companion: kotlinx generates `serializer()` ON the companion, and `parseUsageReport`
    // calls `UsageWindow.serializer()` by hand — making it private hides the generated accessor with it.
    companion object {
        private const val PERCENT = 100
    }
}

/** `rate_limits.extra_usage` — the pay-as-you-go credit balance shown once the plan's windows are spent. */
@Serializable
data class ExtraUsage(
    @SerialName("is_enabled") val isEnabled: Boolean = false,
    @SerialName("monthly_limit") val monthlyLimit: Double? = null,
    @SerialName("used_credits") val usedCredits: Double? = null,
    val utilization: Double? = null,
    val currency: String? = null,
    @SerialName("decimal_places") val decimalPlaces: Int = 2,
    @SerialName("spend_limit_reached") val spendLimitReached: Boolean = false,
    @SerialName("user_disabled") val userDisabled: Boolean = false,
)

/**
 * The `get_usage` control reply, flattened into what a UI actually needs.
 *
 * [windows] is a LIST, not a map, because order is meaning here: the session window first, then the weekly
 * all-models one, then the per-model buckets. Windows the binary reports as `null` (a limit that exists but
 * has not been touched) are dropped rather than rendered as 0% — "untouched" and "zero used" look identical
 * on a progress bar and are not the same claim.
 */
data class UsageReport(
    val subscriptionType: String? = null,
    val available: Boolean = false,
    val windows: List<Pair<String, UsageWindow>> = emptyList(),
    val extra: ExtraUsage? = null,
) {
    val isEmpty: Boolean get() = windows.isEmpty() && extra == null
}
