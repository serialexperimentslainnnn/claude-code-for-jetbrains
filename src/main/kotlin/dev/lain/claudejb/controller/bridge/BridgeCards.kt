package dev.lain.claudejb.controller.bridge

import dev.lain.claudejb.model.bridge.Msg
import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.view.window.JcefChatPanel

internal class BridgeCards(private val panel: JcefChatPanel) {

    fun handle(m: Msg.RequestCard) {
        when (m) {
            is Msg.ResolvePermission -> resolvePermission(m)

            is Msg.ResolveQuestion -> panel.cardSession(m.scope).cards.resolveQuestion(m.id, m.answers)

            is Msg.ResolveElicitation ->
                panel.cardSession(m.scope).cards.resolveElicitation(m.id, m.action, m.content)

            is Msg.AlwaysAllow -> alwaysAllow(m)
        }
    }

    private fun resolvePermission(m: Msg.ResolvePermission) {
        val target = panel.cardSession(m.scope)
        val wasPlan = target.cards.pending().firstOrNull { it.requestId == m.id }?.isPlan == true
        target.cards.resolvePermission(m.id, m.allow)
        if (wasPlan && m.allow && target === panel.session) panel.feed.requestPlan()
    }

    private fun alwaysAllow(m: Msg.AlwaysAllow) {
        ClaudeSettings.getInstance(panel.project).alwaysAllow.remember(m.tool)
        val chat = panel.cardSession(m.scope)
        val pending = chat.cards.pending()
        val target = pending.firstOrNull { it.requestId == m.id } ?: pending.firstOrNull { it.toolName == m.tool }
        target?.let { chat.cards.resolvePermission(it.requestId, true) }
    }
}
