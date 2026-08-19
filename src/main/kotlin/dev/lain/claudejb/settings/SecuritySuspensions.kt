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

    fun suspendUntilIdeCloses(rule: SecurityRule) {
        sessionScoped += rule
    }

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

object SecurityCommandApprovals {

    fun isApproved(lines: String, rule: SecurityRule, command: String?): Boolean {
        val wanted = command?.trim().orEmpty()
        if (wanted.isEmpty()) return false
        return parse(lines).any { it.first == rule && it.second == wanted }
    }

    fun withApproval(lines: String, rule: SecurityRule, command: String?): String {
        val wanted = command?.trim().orEmpty()
        if (wanted.isEmpty()) return lines
        if (isApproved(lines, rule, wanted)) return lines
        return (parse(lines) + (rule to wanted)).joinToString("\n") { "${it.first.name}=${it.second}" }
    }

    private fun parse(lines: String): List<Pair<SecurityRule, String>> =
        lines.lines().mapNotNull { line ->
            val name = line.substringBefore('=', "").trim()
            val command = line.substringAfter('=', "").trim()
            if (command.isEmpty()) return@mapNotNull null
            SecurityRule.from(name)?.let { it to command }
        }
}
