package dev.lain.claudejb.settings

import dev.lain.claudejb.permission.SecurityRule
import java.util.concurrent.ConcurrentHashMap

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

    fun all(): Map<SecurityRule, Set<String>> = approved.entries.associate { it.key to it.value.toSet() }
}
