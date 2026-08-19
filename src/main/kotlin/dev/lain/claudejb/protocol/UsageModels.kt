package dev.lain.claudejb.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime

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

@Serializable
data class SessionCostUsage(
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Long = 0,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Long = 0,
)

@Serializable
data class RateLimitInfo(
    val status: String = "allowed",
    val resetsAt: Long? = null,
    val rateLimitType: String? = null,
    val utilization: Double? = null,
    val overageStatus: String? = null,
    val isUsingOverage: Boolean = false,
    val overageResetsAt: Long? = null,
    val overageInUse: Boolean = false,
    val surpassedThreshold: Double? = null,
) {
    fun utilizationPercent(): Int? =
        utilization?.let { Math.round(it * PERCENT).toInt().coerceIn(0, 100) }

    fun resetsAtIso(): String? = resetsAt?.let { java.time.Instant.ofEpochSecond(it).toString() }

    val isExhausted: Boolean get() = status == "rejected"

    companion object {
        private const val PERCENT = 100

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

@Serializable
data class UsageWindow(
    val utilization: Double? = null,
    @SerialName("resets_at") val resetsAt: String? = null,
    @SerialName("limit_dollars") val limitDollars: Double? = null,
    @SerialName("used_dollars") val usedDollars: Double? = null,
    @SerialName("remaining_dollars") val remainingDollars: Double? = null,
    @SerialName("display_name") val displayName: String? = null,
) {
    fun title(key: String): String =
        displayName?.takeIf { it.isNotBlank() } ?: RateLimitInfo.windowTitleFor(key)

    fun utilizationPercent(): Int? =
        utilization?.let { Math.round(it).toInt().coerceIn(0, PERCENT) }

    fun hasReset(nowMillis: Long): Boolean {
        val at = resetsAt?.takeIf { it.isNotBlank() } ?: return false
        val instant = runCatching { OffsetDateTime.parse(at).toInstant() }
            .recoverCatching { Instant.parse(at) }
            .getOrNull() ?: return false
        return instant.toEpochMilli() <= nowMillis
    }

    companion object {
        private const val PERCENT = 100
    }
}

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

data class UsageReport(
    val subscriptionType: String? = null,
    val available: Boolean = false,
    val windows: List<Pair<String, UsageWindow>> = emptyList(),
    val extra: ExtraUsage? = null,
) {
    val isEmpty: Boolean get() = windows.isEmpty() && extra == null
}
