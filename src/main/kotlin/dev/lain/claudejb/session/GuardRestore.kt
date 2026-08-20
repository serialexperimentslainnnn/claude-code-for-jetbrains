package dev.lain.claudejb.session

import dev.lain.claudejb.permission.PermissionBroker
import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.settings.GuardAlert

/**
 * Puts the guard's own transcript rows back into a restored conversation.
 *
 * They cannot be read out of the binary's file. A refusal is recorded there as an ordinary failed tool
 * result whose text is the plugin's own prose — no rule name anywhere in it — and a bypass is recorded as
 * nothing at all, because the call ran and looked like any other. So the rows come from the alert log, and
 * this is where the two halves are stitched: the file supplies the conversation, the log supplies what the
 * guard did about it, and the `toolUseId` is the identifier both of them know.
 *
 * Pure on purpose. No IDE, no session, no clock — a list in, a list out.
 */
object GuardRestore {

    /**
     * [dtos] with a guard row inserted after each call the log has something to say about.
     *
     * An alert whose `toolUseId` matches nothing is appended at the end rather than dropped: the call it
     * describes may have fallen off the transcript's tail cap, and a row saying a rule fired is worth more
     * out of position than not at all.
     */
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

    /** One alert as the row it was live, or null when it is not a row at all. */
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

            // A card was shown. Whichever way it was answered is its own entry, and that is the one that
            // becomes a row — this one would only duplicate it.
            else -> null
        }
    }

    private fun why(via: String?): String = when (via) {
        PermissionBroker.ENABLE_GUARD -> "allowed because the Sensitive Guard is disabled"
        PermissionBroker.REVOKE_APPROVAL -> "allowed because you gave Allow All for this exact command in this chat"
        PermissionBroker.REMOVE_FROM_WHITELIST -> "allowed by a whitelist"
        else -> "allowed by a bypass"
    }

    /**
     * What the restored row may still offer to undo.
     *
     * An *Allow All* given on a card lived in memory and died with the IDE, so the row comes back without
     * its link: offering to withdraw an authorisation that no longer exists would be a lie told by the one
     * surface that exists so the user is not lied to.
     */
    private fun surviving(via: String?): String? =
        via.takeIf { it == PermissionBroker.ENABLE_GUARD || it == PermissionBroker.REMOVE_FROM_WHITELIST }
}
