package dev.lain.claudejb.model.settings.guard

import dev.lain.claudejb.model.permission.vocab.SecurityRule
import dev.lain.claudejb.model.settings.ClaudeSettings
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

    private val sessionScoped = ConcurrentHashMap<String, MutableSet<SecurityRule>>()

    private val guardOffForSession = ConcurrentHashMap.newKeySet<String>()

    private fun rulesFor(scope: String) = sessionScoped.computeIfAbsent(scope) { ConcurrentHashMap.newKeySet() }

    fun suspendUntilIdeCloses(scope: String, rule: SecurityRule) {
        rulesFor(scope) += rule
    }

    fun suspend(scope: String, state: ClaudeSettings.State, rule: SecurityRule, duration: Duration, now: Long) {
        when (duration) {
            Duration.FOREVER ->
                state.disabledSecurityRules =
                    SecurityRule.canonicalCsv((csvItems(state.disabledSecurityRules) + rule.name).distinct())

            Duration.UNTIL_IDE_CLOSES -> suspendUntilIdeCloses(scope, rule)

            else ->
                state.securityRuleSuspensions =
                    withSuspension(state.securityRuleSuspensions, rule, duration.millis ?: 0, now)
        }
    }

    private fun csvItems(csv: String): List<String> = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun guardOff(scope: String, state: ClaudeSettings.State, duration: Duration, now: Long) = when (duration) {
        Duration.FOREVER -> state.guardMode = GuardMode.ALLOW_ALL.wire

        Duration.UNTIL_IDE_CLOSES -> {
            guardOffForSession += scope
            Unit
        }

        else -> state.guardDisabledUntil = now + (duration.millis ?: 0)
    }

    fun guardOn(scope: String, state: ClaudeSettings.State) {
        if (GuardMode.from(state.guardMode) == GuardMode.ALLOW_ALL) state.guardMode = GuardMode.DEFAULT.wire
        state.guardDisabledUntil = 0
        guardOffForSession -= scope
    }

    fun guardSuspended(scope: String, state: ClaudeSettings.State, now: Long): Boolean =
        GuardMode.from(state.guardMode) == GuardMode.ALLOW_ALL ||
            scope in guardOffForSession ||
            state.guardDisabledUntil > now

    fun guardSuspendedUntil(state: ClaudeSettings.State, now: Long): Long? =
        state.guardDisabledUntil.takeIf { it > now }

    fun sessionSuspended(scope: String): Set<SecurityRule> = sessionScoped[scope]?.toSet().orEmpty()

    fun releaseSessionScoped(scope: String, rule: SecurityRule) {
        sessionScoped[scope]?.remove(rule)
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
