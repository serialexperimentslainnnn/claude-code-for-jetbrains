package dev.lain.claudejb.settings

import dev.lain.claudejb.permission.SecurityRule
import java.util.concurrent.ConcurrentHashMap

object SecuritySuspensions {

    enum class Duration(val token: String, val label: String, val phrase: String, val millis: Long?) {
        MINUTES_5("5m", "5 minutes", "for the next 5 minutes", FIVE_MINUTES),
        MINUTES_15("15m", "15 minutes", "for the next 15 minutes", FIFTEEN_MINUTES),
        MINUTES_30("30m", "30 minutes", "for the next 30 minutes", THIRTY_MINUTES),
        HOURS_4("4h", "4 hour", "for the next 4 hours", FOUR_HOURS),
        HOURS_8("8h", "8 hour", "for the next 8 hours", EIGHT_HOURS),

        UNTIL_IDE_CLOSES("ide", "Until IDE closes", "until this IDE closes", null),

        FOREVER("forever", "Forever", "until you enable it again", null),
        ;

        companion object {
            fun from(token: String?): Duration? = entries.firstOrNull { it.token == token?.trim() }
        }
    }

    private val sessionScoped = ConcurrentHashMap.newKeySet<SecurityRule>()

    /**
     * The master switch's *Until IDE closes* choice — the one duration that has nowhere to be written.
     *
     * Its two persisted siblings live on the settings document ([ClaudeSettings.State.guardEnabled] for
     * *Forever*, [ClaudeSettings.State.guardDisabledUntil] for the five that expire), which is the same
     * three-store shape a single suspended rule already uses.
     */
    @Volatile
    private var guardOffForSession = false

    fun suspendUntilIdeCloses(rule: SecurityRule) {
        sessionScoped += rule
    }

    /** Opens the whole guard for [duration], writing to whichever of the three stores that duration needs. */
    fun guardOff(state: ClaudeSettings.State, duration: Duration, now: Long) = when (duration) {
        Duration.FOREVER -> state.guardEnabled = false
        Duration.UNTIL_IDE_CLOSES -> guardOffForSession = true
        else -> state.guardDisabledUntil = now + (duration.millis ?: 0)
    }

    /** Enforces the guard again, and clears **all three** stores — otherwise one of them silently outlives it. */
    fun guardOn(state: ClaudeSettings.State) {
        state.guardEnabled = true
        state.guardDisabledUntil = 0
        guardOffForSession = false
    }

    fun guardSuspended(state: ClaudeSettings.State, now: Long): Boolean =
        !state.guardEnabled || guardOffForSession || state.guardDisabledUntil > now

    /** When the timed suspension runs out, or null when nothing timed is open. */
    fun guardSuspendedUntil(state: ClaudeSettings.State, now: Long): Long? =
        state.guardDisabledUntil.takeIf { it > now }

    fun sessionSuspended(): Set<SecurityRule> = sessionScoped.toSet()

    fun releaseSessionScoped(rule: SecurityRule) {
        sessionScoped -= rule
    }

    fun active(csv: String, now: Long): Set<SecurityRule> =
        parse(csv).filterValues { it > now }.keys

    fun withSuspension(csv: String, rule: SecurityRule, millis: Long, now: Long): String =
        format(parse(csv).filterValues { it > now } + (rule to now + millis))

    fun without(csv: String, rule: SecurityRule, now: Long): String =
        format(parse(csv).filterValues { it > now } - rule)

    private fun parse(csv: String): Map<SecurityRule, Long> =
        csv.split(',').mapNotNull { entry ->
            val name = entry.substringBefore('=', "").trim()
            val at = entry.substringAfter('=', "").trim().toLongOrNull() ?: return@mapNotNull null
            SecurityRule.from(name)?.let { it to at }
        }.toMap()

    private fun format(entries: Map<SecurityRule, Long>): String =
        entries.entries.joinToString(",") { "${it.key.name}=${it.value}" }

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE

    private const val FIVE_MINUTES = 5 * MINUTE
    private const val FIFTEEN_MINUTES = 15 * MINUTE
    private const val THIRTY_MINUTES = 30 * MINUTE
    private const val FOUR_HOURS = 4 * HOUR
    private const val EIGHT_HOURS = 8 * HOUR
}
