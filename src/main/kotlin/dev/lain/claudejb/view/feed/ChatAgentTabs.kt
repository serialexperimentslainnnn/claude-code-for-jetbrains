package dev.lain.claudejb.view.feed

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.wm.ToolWindowManager
import dev.lain.claudejb.controller.session.history.PluginAgentIndex
import dev.lain.claudejb.model.bridge.JcefBridge
import dev.lain.claudejb.model.bridge.Msg
import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.util.logger
import dev.lain.claudejb.view.payload.chat.JcefTabsData
import dev.lain.claudejb.view.window.ClaudeToolWindowFactory
import dev.lain.claudejb.view.window.JcefChatPanel

internal class ChatAgentTabs(private val panel: JcefChatPanel) {

    private val project get() = panel.project
    private val session get() = panel.session

    private val hiddenAgents = HashSet<String>()

    private var chats: List<JcefTabsData.Chat> = emptyList()

    init {
        session.sessionId?.let { id ->
            val index = PluginAgentIndex.getInstance(project)
            hiddenAgents += index.admittedAgents(id) - index.openAgents(id).toSet()
        }
    }

    fun render() {
        val strip = panel.chatStrip()
        val chats = strip?.chatList() ?: this.chats
        if (chats.isEmpty()) {
            LOG.warn("Claude Code tab bar: nothing to draw (strip=${strip != null}, cached=${this.chats.size})")
        }
        val windowMinutes = ClaudeSettings.getInstance(project).workloadWindowMinutes
        panel.host.exec(
            "window.cc.tabs && window.cc.tabs(" +
                JcefTabsData.tabsJson(
                    session,
                    chats,
                    hiddenAgents,
                    windowMinutes,
                    System.currentTimeMillis(),
                ) + ")",
        )
    }

    fun setChats(list: List<JcefTabsData.Chat>) {
        chats = list
        render()
    }

    fun onAgentsScanned(freshlyAdmitted: List<String>) {
        render()
        val fresh = freshlyAdmitted
            .filterNot { it in hiddenAgents }
            .filter { session.runningAgents.nodes[it]?.status == dev.lain.claudejb.model.session.agents.AgentStatus.RUNNING }
        if (fresh.isEmpty()) return
        notifyAgentsSpawned(fresh)
    }

    private fun notifyAgentsSpawned(fresh: List<String>) {
        val tw = ToolWindowManager.getInstance(project).getToolWindow(ClaudeToolWindowFactory.TOOL_WINDOW_ID)
        if (tw != null && tw.isVisible && panel.isShowing) return
        val names = fresh.mapNotNull { session.runningAgents.nodes[it]?.meta?.label() }
        val text = when {
            names.size == 1 -> "Agent started in \"${session.title}\": ${names.first()}"
            names.isNotEmpty() -> "${names.size} agents started in \"${session.title}\""
            else -> return
        }
        NotificationGroupManager.getInstance().getNotificationGroup("Claude Code")
            .createNotification("Claude Code", text, NotificationType.INFORMATION)
            .addAction(
                NotificationAction.createSimpleExpiring("Open") {
                    fresh.firstOrNull()?.let { revealAgent(it) }
                    ToolWindowManager.getInstance(project).getToolWindow(ClaudeToolWindowFactory.TOOL_WINDOW_ID)?.activate(null)
                },
            )
            .notify(project)
    }

    fun revealElsewhere(chatId: String, reveal: (JcefChatPanel) -> Unit) {
        val strip = panel.chatStrip()
        val target = chatId.takeIf { it.isNotBlank() }?.let { strip?.panelOf(it) }
        if (target == null || target === panel) {
            reveal(panel)
            return
        }
        strip?.selectById(chatId)
        reveal(target)
    }

    fun revealFromHost(m: Msg.RevealAgent) {
        resolveAgentId(m)?.let { revealAgent(it) } ?: panel.transcript.showTranscript(null)
    }

    private fun revealAgent(agentId: String) {
        if (hiddenAgents.remove(agentId)) {
            session.sessionId?.let { PluginAgentIndex.getInstance(project).setTabOpen(it, agentId, true) }
            render()
        }
        panel.host.exec(
            "window.cc.revealAgentTab && window.cc.revealAgentTab(" + JcefBridge.jsString(agentId) + ")",
        )
        panel.transcript.showTranscript(agentId)
    }

    fun closeAgent(agentId: String) {
        hiddenAgents += agentId
        session.sessionId?.let { PluginAgentIndex.getInstance(project).setTabOpen(it, agentId, false) }
        panel.transcript.showTranscript(null)
        render()
    }

    private fun resolveAgentId(m: Msg.RevealAgent): String? {
        m.agentId.takeIf { it.isNotBlank() }?.let { return it }
        val tool = m.toolUseId.takeIf { it.isNotBlank() } ?: return null
        return session.runningAgents.nodes.values.firstOrNull { it.meta.toolUseId == tool }?.agentId
    }

    private companion object {
        val LOG = logger<ChatAgentTabs>()
    }
}
