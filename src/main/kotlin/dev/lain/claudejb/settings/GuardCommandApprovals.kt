package dev.lain.claudejb.settings

import dev.lain.claudejb.permission.SecurityRule
import java.util.concurrent.ConcurrentHashMap

/**
 * The commands the user pre-approved from a guard alert — **one store per chat, in memory only**.
 *
 * This is the narrow half of the two ways a watched command gets through, and the difference is the whole
 * point of it existing separately from the whitelists in Settings: answering *Always allow this command* on
 * a card authorises that command **in this conversation, until the IDE closes**, and nothing is written
 * anywhere. The whitelists are the wide half — this IDE, this project, until the user deletes the entry —
 * and they are authored in the cold, on the Settings page, rather than under a card while impatient.
 *
 * It is deliberately NOT reachable from the tool-level *Always allow* set ([AlwaysAllowTools]): the guard
 * runs before any of that, and remembering a TOOL can never answer for a COMMAND the guard stopped.
 */
class GuardCommandApprovals {

    private val approved = ConcurrentHashMap<SecurityRule, MutableSet<String>>()

    fun isApproved(rule: SecurityRule, command: String?): Boolean {
        val wanted = command?.trim().orEmpty()
        if (wanted.isEmpty()) return false
        return approved[rule]?.contains(wanted) == true
    }

    fun approve(rule: SecurityRule, command: String?) {
        val wanted = command?.trim().orEmpty()
        if (wanted.isEmpty()) return
        approved.computeIfAbsent(rule) { ConcurrentHashMap.newKeySet() }.add(wanted)
    }

    fun revoke(rule: SecurityRule, command: String) {
        approved[rule]?.remove(command)
    }

    /** Everything approved in this chat so far, for the Settings page to show and revoke. */
    fun all(): Map<SecurityRule, Set<String>> = approved.entries.associate { it.key to it.value.toSet() }
}
