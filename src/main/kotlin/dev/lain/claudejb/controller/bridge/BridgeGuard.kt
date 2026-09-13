package dev.lain.claudejb.controller.bridge

import dev.lain.claudejb.controller.commands.GuardWhitelistPrompt
import dev.lain.claudejb.controller.commands.LivePanels
import dev.lain.claudejb.model.bridge.Msg
import dev.lain.claudejb.model.permission.SensitiveGuard
import dev.lain.claudejb.model.permission.scan.ToolInputScanner
import dev.lain.claudejb.model.permission.vocab.SecurityRule
import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.model.settings.guard.GuardWhitelists
import dev.lain.claudejb.model.settings.guard.SecuritySuspensions
import dev.lain.claudejb.model.settings.guard.sensitivePolicy
import dev.lain.claudejb.util.thisLogger
import dev.lain.claudejb.view.window.JcefChatPanel

internal class BridgeGuard(private val panel: JcefChatPanel) {

    private val log = thisLogger()

    private val session get() = panel.session

    private val settings: ClaudeSettings get() = ClaudeSettings.getInstance(panel.project)

    fun handle(m: Msg.Guard) {
        when (m) {
            is Msg.GuardSuspend -> suspend(m)
            is Msg.GuardMaster -> master(m)
            is Msg.GuardWhitelist -> whitelist(m)
            is Msg.GuardRevokeApproval -> revokeApproval(m)
            is Msg.GuardRemoveWhitelist -> removeWhitelist(m)
            is Msg.GuardAllowAlways -> allowAlways(m)
            Msg.GuardLog -> panel.security.pushGuard()
            is Msg.GuardExplain -> panel.guard.explain(m.id)
        }
    }

    private fun suspend(m: Msg.GuardSuspend) {
        val rule = SecurityRule.from(m.rule)
        val duration = SecuritySuspensions.Duration.from(m.duration)
        if (rule == null || duration == null) {
            log.warn("A guard block asked to suspend something this build does not have: ${m.rule}/${m.duration}")
            return
        }
        val scope = settings.scope.id
        settings.update { SecuritySuspensions.suspend(scope, it, rule, duration, System.currentTimeMillis()) }
        LivePanels.pushSettingsMenu()
        session.systemNotice(
            "${rule.label} is disabled ${duration.phrase}. Matching calls will ask you instead of being refused.",
        )
    }

    private fun master(m: Msg.GuardMaster) {
        val scope = settings.scope.id
        if (m.on) {
            settings.update { SecuritySuspensions.guardOn(scope, it) }
            announce("The Sensitive Guard is back on. Every tool call is judged again.")
            return
        }
        val duration = SecuritySuspensions.Duration.from(m.duration)
        if (duration == null) {
            log.warn("The shield asked to stand down for a duration this build does not have: ${m.duration}")
            return
        }
        settings.update { SecuritySuspensions.guardOff(scope, it, duration, System.currentTimeMillis()) }
        announce(
            "The Sensitive Guard is off ${duration.phrase}. Nothing is being judged — no rule, no card, " +
                "no block — until it comes back on.",
        )
    }

    private fun announce(notice: String) {
        LivePanels.pushSettingsMenu()
        LivePanels.pushState()
        session.systemNotice(notice)
    }

    private fun whitelist(m: Msg.GuardWhitelist) {
        val rule = SecurityRule.from(m.rule)
        val entry = GuardWhitelists.entryFor(m.command)
        if (rule == null || entry.isEmpty()) {
            log.warn("A guard block asked to whitelist something this build cannot place: " + m.rule)
            return
        }
        if (!GuardWhitelistPrompt.confirm(panel.project, rule, entry)) return
        val policy = settings.sensitivePolicy(panel.project.basePath)
        val canonical = SensitiveGuard.canonicalCommand(entry, policy)
        val already =
            GuardWhitelists.all(settings.state, rule).any { SensitiveGuard.canonicalCommand(it, policy) == canonical }
        if (already) {
            session.systemNotice("`" + entry + "` is already whitelisted — nothing added.")
            return
        }
        settings.update { GuardWhitelists.add(it, rule, entry) }
        LivePanels.pushSettingsMenu()
        session.systemNotice(
            "Commands starting with `" + entry + "` are whitelisted for " + rule.label + ". Every other rule still judges them.",
        )
    }

    private fun removeWhitelist(m: Msg.GuardRemoveWhitelist) {
        val rule = SecurityRule.from(m.rule)
        if (rule == null || m.command.isBlank()) {
            log.warn("A bypass warning asked to un-whitelist something this build cannot place: ${m.rule}")
            return
        }
        val policy = settings.sensitivePolicy(panel.project.basePath)
        val wanted = SensitiveGuard.canonicalCommand(m.command, policy)
        val covers = { entry: String -> SensitiveGuard.covers(SensitiveGuard.canonicalCommand(entry, policy), wanted) }
        val listed = GuardWhitelists.listedIn(settings.state, rule, covers)
        if (listed.isEmpty()) {
            session.systemNotice("`${m.command.trim()}` is not on any whitelist any more.")
            return
        }
        settings.update { GuardWhitelists.remove(it, rule, listed, covers) }
        LivePanels.pushSettingsMenu()
        val where = listed.joinToString(" and ") { describe(it, rule) }
        session.systemNotice("`${m.command.trim()}` is off the $where. ${rule.label} decides it again.")
    }

    private fun describe(listed: GuardWhitelists.Listed, rule: SecurityRule): String = when (listed) {
        GuardWhitelists.Listed.RULE -> "whitelist for ${rule.label}"
        GuardWhitelists.Listed.CATEGORY -> "whitelist for ${rule.category.label}"
        GuardWhitelists.Listed.EVERYWHERE -> "whitelist that applies everywhere"
    }

    private fun revokeApproval(m: Msg.GuardRevokeApproval) {
        val rule = SecurityRule.from(m.rule)
        if (rule == null || m.command.isBlank()) {
            log.warn("A bypass warning asked to revoke something this build cannot place: ${m.rule}")
            return
        }
        session.guard.approvals.revoke(rule, m.command.trim())
        LivePanels.pushSettingsMenu()
        session.systemNotice("`${m.command.trim()}` is no longer pre-approved. ${rule.label} decides again.")
    }

    private fun allowAlways(m: Msg.GuardAllowAlways) {
        val chat = panel.cardSession(m.scope)
        val target = chat.cards.pending().firstOrNull { it.requestId == m.id } ?: return
        val rule = target.guard?.rule ?: return
        ToolInputScanner.commandsIn(target.input).forEach { chat.guard.approvals.approve(rule, it) }
        chat.cards.resolvePermission(target.requestId, true)
    }
}
