package dev.lain.claudejb.session

import dev.lain.claudejb.permission.PermissionBroker
import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.settings.GuardAlert

object GuardRestore {

    fun reinstate(dtos: List<EntryDTO>, alerts: List<GuardAlert>): List<EntryDTO> {
        val rows = alerts.mapNotNull(::rowFor)
        if (rows.isEmpty()) return dtos

        val byAnchor = rows.filter { it.first != null }.groupBy({ it.first }, { it.second })
        val placed = mutableSetOf<String>()
        val out = mutableListOf<EntryDTO>()
        for (dto in dtos) {
            out.add(dto)
            val anchor = dto.toolUseId ?: continue
            if (!placed.add(anchor)) continue
            byAnchor[anchor]?.let(out::addAll)
        }
        out.addAll(rows.filter { it.first == null || it.first !in placed }.map { it.second })
        return out
    }

    private fun rowFor(alert: GuardAlert): Pair<String?, EntryDTO>? {
        val rule = SecurityRule.from(alert.rule) ?: return null
        val what = alert.detail?.let { " — it $it" }.orEmpty()
        val tool = alert.tool ?: "the call"
        return when (alert.verdict) {
            GuardAlert.DENIED -> alert.toolUseId to EntryDTO(
                speaker = "SYSTEM",
                text = alert.detail?.let { "Blocked $tool: it $it." } ?: "Blocked $tool by the sensitive-data guard.",
                commandText = alert.command,
                blockedRule = rule.name,
            )

            GuardAlert.ALLOWED -> alert.toolUseId to EntryDTO(
                speaker = "SYSTEM",
                text = "Allowed $tool: ${rule.label} matched$what — ${why(alert.via)}.",
                commandText = alert.command,
                bypassedRule = rule.name,
                bypassAction = surviving(alert.via),
            )

            else -> null
        }
    }

    private fun why(via: String?): String = when (via) {
        PermissionBroker.ENABLE_GUARD -> "allowed because the Sensitive Guard is disabled"
        PermissionBroker.REVOKE_APPROVAL -> "allowed because you gave Allow All for this exact command in this chat"
        PermissionBroker.REMOVE_FROM_WHITELIST -> "allowed by a whitelist"
        else -> "allowed by a bypass"
    }

    private fun surviving(via: String?): String? =
        via.takeIf { it == PermissionBroker.ENABLE_GUARD || it == PermissionBroker.REMOVE_FROM_WHITELIST }
}
