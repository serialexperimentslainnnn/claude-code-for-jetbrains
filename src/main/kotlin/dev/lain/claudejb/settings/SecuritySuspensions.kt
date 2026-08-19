package dev.lain.claudejb.settings

import dev.lain.claudejb.permission.SecurityRule
import java.util.concurrent.ConcurrentHashMap

/**
 * How long a security rule stays open, for the rule the user suspended from a block in the transcript.
 *
 * **Why a suspension exists at all, and why it is not simply the existing toggle.** Until now the only way to
 * open a rule was Settings ▸ Security, which turns it off **permanently** — until the user remembers to turn it
 * back on, which is the one part nobody does. So the honest description of the old surface is "the only unblock
 * we offer is the most dangerous one". Every choice here except [Duration.FOREVER] expires on its own, so the
 * lock spends *less* time open than it did, not more.
 *
 * **What a suspension can never do.** It suspends exactly one rule, and a suspended rule does not become a
 * silent allow: `SensitiveGuard.evaluate` downgrades a disabled rule's hit to ASK, so every matching call still
 * stops and puts a card to the user — see [dev.lain.claudejb.settings.ClaudeSettings.State.disabledSecurityRules].
 * That is what makes offering this from a block defensible: the click buys a question, not a pass.
 *
 * Three storages, because the three lifetimes are genuinely different and collapsing them loses information:
 *  - **timed** ([Duration.MINUTES_5] … [Duration.HOURS_8]) → the persisted
 *    [ClaudeSettings.State.securityRuleSuspensions], as `RULE=<epochMillis>`. Persisted because an 8-hour
 *    suspension that an IDE restart silently cancelled would be a control that lies about its own duration;
 *  - **until the IDE closes** ([Duration.UNTIL_IDE_CLOSES]) → [sessionScoped], process state, never written.
 *    An IDE restart is the expiry, so writing it down is how it would outlive its own meaning;
 *  - **forever** ([Duration.FOREVER]) → the existing permanent CSV, since "no expiry" is what that field is.
 *
 * Time is a parameter ([now]) so the expiry rule is testable without waiting for a clock.
 */
object SecuritySuspensions {

    /**
     * The choices offered on a block, in the order they are shown.
     *
     * [label] is the menu entry the user clicks; [phrase] is how the same choice reads inside the confirming
     * sentence, which is not the same string and cannot be derived from the first — "Forever" as a menu entry
     * is right, and "disabled for forever" is not English. Both live here so the vocabulary has one owner.
     */
    enum class Duration(val token: String, val label: String, val phrase: String, val millis: Long?) {
        MINUTES_5("5m", "5 minutes", "for the next 5 minutes", FIVE_MINUTES),
        MINUTES_15("15m", "15 minutes", "for the next 15 minutes", FIFTEEN_MINUTES),
        MINUTES_30("30m", "30 minutes", "for the next 30 minutes", THIRTY_MINUTES),
        HOURS_4("4h", "4 hour", "for the next 4 hours", FOUR_HOURS),
        HOURS_8("8h", "8 hour", "for the next 8 hours", EIGHT_HOURS),

        /** Process state: the expiry is the IDE going away, so nothing is persisted. */
        UNTIL_IDE_CLOSES("ide", "Until IDE closes", "until this IDE closes", null),

        /**
         * No expiry — the permanent CSV, i.e. exactly what the Settings toggle already does. The phrase says so
         * rather than saying "for ever": what actually ends it is the user, and the sentence should name that.
         */
        FOREVER("forever", "Forever", "until you enable it again", null),
        ;

        companion object {
            /** `null` for a token this build does not know, which is a no-op rather than a guessed duration. */
            fun from(token: String?): Duration? = entries.firstOrNull { it.token == token?.trim() }
        }
    }

    /**
     * Rules suspended until this IDE closes. Application-scoped and concurrent: written from the EDT when a
     * link is clicked, read from the process reader thread on every `can_use_tool`.
     *
     * A SET and not a map: there is no instant to compare against, which is the whole meaning of the choice.
     */
    private val sessionScoped = ConcurrentHashMap.newKeySet<SecurityRule>()

    /** Suspends [rule] until the IDE closes. */
    fun suspendUntilIdeCloses(rule: SecurityRule) {
        sessionScoped += rule
    }

    /** Whatever is suspended for this process only — see [sessionScoped]. */
    fun sessionSuspended(): Set<SecurityRule> = sessionScoped.toSet()

    /** Cancels [rule]'s process-scoped suspension — what enforcing it again from a switch has to do. */
    fun releaseSessionScoped(rule: SecurityRule) {
        sessionScoped -= rule
    }

    /**
     * The timed suspensions still in force at [now], parsed from [csv].
     *
     * An unparseable entry is DROPPED, never defaulted: a stale name or a garbled instant can then only fail to
     * open a rule, which is the direction that costs a card rather than a credential.
     */
    fun active(csv: String, now: Long): Set<SecurityRule> =
        parse(csv).filterValues { it > now }.keys

    /**
     * [csv] with [rule] suspended for [millis] from [now], and every already-expired entry removed.
     *
     * Pruning on write rather than on read is what keeps the stored document from growing for ever while
     * leaving the hot path ([active]) a pure parse — that path runs on the thread reading the binary's stdout.
     */
    fun withSuspension(csv: String, rule: SecurityRule, millis: Long, now: Long): String =
        format(parse(csv).filterValues { it > now } + (rule to now + millis))

    /** [csv] with [rule] released, and expired entries pruned — the undo of [withSuspension]. */
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

    // The five bounded choices, spelled out so the menu and the arithmetic cannot drift apart.
    private const val FIVE_MINUTES = 5 * MINUTE
    private const val FIFTEEN_MINUTES = 15 * MINUTE
    private const val THIRTY_MINUTES = 30 * MINUTE
    private const val FOUR_HOURS = 4 * HOUR
    private const val EIGHT_HOURS = 8 * HOUR
}

/**
 * The exact commands the user answered "Allow always" to **on a guard card**, per rule.
 *
 * **Why per command and per rule, and never per tool.** "Always allow" on an ordinary card remembers a TOOL,
 * which is the right grain there and catastrophic here: `Bash` covers every command there is, so one click on a
 * `terraform destroy` card would open every other destructive command the same rule exists to stop. So the unit
 * of the answer is the command that was on the card, and it is filed under the rule that blocked it.
 *
 * **Why nothing here ever needs cleaning up.** An approval is only ever honoured while its rule is still
 * suspended or disabled — the check is a conjunction, not a lookup ([isApproved] is asked *after* the rule is
 * known to be open). So re-enabling the rule, or letting its suspension expire, revokes every command approved
 * under it without a single write. The entry may linger in the document; it cannot act.
 *
 * **What it can never become.** It is an exact, whole-command match, so it authorises the one command the user
 * read on the card and nothing adjacent to it: not the rule, not the tool, not a prefix, not the same command
 * with another target. That is precisely the property [dev.lain.claudejb.permission.SensitiveGuard]'s own
 * whitelist has, and it is why an approval taken mid-turn is defensible at all.
 *
 * Stored line-per-entry as `RULE=<command>`, for the same reason
 * [ClaudeSettings.State.securityCommandWhitelist] is: a command contains commas.
 */
object SecurityCommandApprovals {

    /** True when [command] was approved under [rule]. Blank commands never match — see [withApproval]. */
    fun isApproved(lines: String, rule: SecurityRule, command: String?): Boolean {
        val wanted = command?.trim().orEmpty()
        if (wanted.isEmpty()) return false
        return parse(lines).any { it.first == rule && it.second == wanted }
    }

    /**
     * [lines] with [command] approved under [rule].
     *
     * A blank command is NOT stored, and that is a fail-safe and not an omission: a call with no command text
     * (a file write, an MCP call) has nothing exact to remember, and storing an empty string would make the
     * approval match every such call under that rule — the tool-wide bypass this whole design exists to avoid.
     */
    fun withApproval(lines: String, rule: SecurityRule, command: String?): String {
        val wanted = command?.trim().orEmpty()
        if (wanted.isEmpty()) return lines
        if (isApproved(lines, rule, wanted)) return lines
        return (parse(lines) + (rule to wanted)).joinToString("\n") { "${it.first.name}=${it.second}" }
    }

    /** A command's own text is multi-line often enough that only the FIRST line is compared. */
    private fun parse(lines: String): List<Pair<SecurityRule, String>> =
        lines.lines().mapNotNull { line ->
            val name = line.substringBefore('=', "").trim()
            val command = line.substringAfter('=', "").trim()
            if (command.isEmpty()) return@mapNotNull null
            SecurityRule.from(name)?.let { it to command }
        }
}
