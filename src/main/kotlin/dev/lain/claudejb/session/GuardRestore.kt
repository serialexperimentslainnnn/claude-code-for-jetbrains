package dev.lain.claudejb.session

import dev.lain.claudejb.permission.PermissionBroker
import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.settings.GuardAlert

object GuardRestore {

    fun reinstate(dtos: List<EntryDTO>, alerts: List<GuardAlert>): List<EntryDTO> {
        val rows = alerts
            .filter { it.at > 0 }
            .mapNotNull { alert -> rowFor(alert)?.let { Row(alert.at, it.first, it.second) } }
        if (rows.isEmpty()) return dtos

        val parentOfAnchor = dtos.mapNotNull { dto -> dto.toolUseId?.let { it to dto.parentToolUseId } }.toMap()
        val anchored = rows.map { row ->
            row.copy(entry = row.entry.copy(parentToolUseId = row.anchor?.let { parentOfAnchor[it] }))
        }
        val byAnchor = anchored.filter { it.anchor != null }.groupBy({ it.anchor }, { it.entry })

        val placed = mutableSetOf<String>()
        val datable = dtos.any { it.atMillis != null }
        val loose = anchored
            .filter { datable && (it.anchor == null || it.anchor !in parentOfAnchor.keys) }
            .sortedBy { it.at }
        val out = mutableListOf<EntryDTO>()
        var next = 0
        for (dto in dtos) {
            val stamp = dto.atMillis
            while (stamp != null && next < loose.size && loose[next].at <= stamp) {
                out.add(loose[next].entry)
                next++
            }
            out.add(dto)
            val anchor = dto.toolUseId ?: continue
            if (!placed.add(anchor)) continue
            byAnchor[anchor]?.let(out::addAll)
        }
        while (next < loose.size) {
            out.add(loose[next].entry)
            next++
        }
        return out
    }

    private data class Row(val at: Long, val anchor: String?, val entry: EntryDTO)

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
