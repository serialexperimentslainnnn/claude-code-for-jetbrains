package dev.lain.claudejb.ui

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.wm.ToolWindowManager
import dev.lain.claudejb.session.PluginAgentIndex
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.ui.jcef.JcefBridge
import dev.lain.claudejb.ui.jcef.JcefTabsData

/**
 * The tab bar this page draws, and everything reached from it: which agents are open, which the user closed,
 * revealing one (here or in another chat), pinning one as a tab of its own, and announcing new ones.
 *
 * Extracted from `JcefChatPanel`, which is an assembler. The bar itself is WEB (`app-tabs.js` +
 * [JcefTabsData]); this is its host side. EDT-confined, like the panel.
 */
internal class ChatAgentTabs(private val panel: JcefChatPanel) {

    private val project get() = panel.project
    private val session get() = panel.session

    /** Agents whose tab the user closed. Not a delete — see [PluginAgentIndex]; the card reopens them. */
    private val hiddenAgents = HashSet<String>()

    /**
     * The chat list this page draws in its tab bar.
     *
     * Pushed in by [ChatTabsPanel] (see [setChats]) because no single page owns the list — there is one
     * browser per chat, and each renders the whole bar marking its own entry.
     */
    private var chats: List<JcefTabsData.Chat> = emptyList()

    init {
        // What the user closed in an earlier run stays closed: the index is read once here, before the first
        // scan can render anything, so a restored chat never flashes a tab the user had dismissed.
        session.sessionId?.let { id ->
            val index = PluginAgentIndex.getInstance(project)
            hiddenAgents += index.admittedAgents(id) - index.openAgents(id).toSet()
        }
    }

    /**
     * Repaints the row stack from the registry, minus whatever the user has closed.
     *
     * The owner of a background task is resolved through the edge stream: `background_tasks_changed` carries
     * no parent, but the same `task_id` seen earlier as a subagent task does carry the `tool_use_id` that
     * names an agent. When that lookup fails the task stays at the chat's level rather than being guessed
     * into somebody's row.
     */
    fun render() {
        // Every open chat's session, so hovering ANY tab can show that chat's tree — not just this one's.
        val others = panel.chatStrip()?.workloads().orEmpty().associate { it.chatId to it.session }
        // The retention window and the instant to measure it from, read ONCE for the whole push: every chat
        // in this bar is then aged by the same instant, instead of the last one drawn being younger than the
        // first by however long the serialisation took.
        val windowMinutes = ClaudeSettings.getInstance(project).workloadWindowMinutes
        panel.host.exec(
            "window.cc.tabs && window.cc.tabs(" +
                JcefTabsData.tabsJson(
                    session,
                    chats,
                    hiddenAgents,
                    windowMinutes,
                    System.currentTimeMillis(),
                    others,
                ) + ")",
        )
    }

    /** The chat list changed (added, closed, renamed, selected): re-render this page's bar. */
    fun setChats(list: List<JcefTabsData.Chat>) {
        chats = list
        render()
    }

    /**
     * A scan finished. Repaint the rows, then blink the tabs of agents seen for the first time and raise ONE
     * grouped notification for the burst — on a session spawning dozens at once, one popup per agent is a
     * storm, and the blink is what carries "this one is new" without interrupting.
     */
    fun onAgentsScanned(freshlyAdmitted: List<String>) {
        render()
        val fresh = freshlyAdmitted
            .filterNot { it in hiddenAgents }
            // "Started" means STARTED. A restored chat admits its whole history at once, and every one of
            // those agents is freshly admitted as far as the registry is concerned — announcing them said
            // "12 agents started" for work that ended before the IDE was even open. Only something actually
            // running is news.
            .filter { session.runningAgents.nodes[it]?.status == dev.lain.claudejb.session.AgentStatus.RUNNING }
        if (fresh.isEmpty()) return
        notifyAgentsSpawned(fresh)
    }

    /**
     * One IDE notification per burst of spawns, with a link into the tool window.
     *
     * Grouped rather than one-per-agent because the session this exists for spawns them in waves: dozens of
     * popups say nothing except "stop looking at the IDE". Suppressed entirely when this chat is the one on
     * screen — the blinking tab has already said it, and a popup for what you are looking at is noise.
     */
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

    /**
     * Runs [reveal] on the panel that OWNS the thing, selecting its chat first when that is not this one.
     *
     * Workloads draws every chat, but its clicks arrive at whichever panel is on screen. Without this the
     * panel searched its own session for somebody else's agent, found nothing, and the click did nothing —
     * while the identical node in the tab bar's popup worked, because there the owner is always this panel.
     *
     * Selecting first, then revealing: the strip's `select` shows the chat's own transcript as part of the
     * switch, so revealing before it would be undone a moment later.
     */
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

    /** The host side of a `revealAgent`: resolve what it names, or fall back to the chat's own transcript. */
    fun revealFromHost(m: JcefBridge.Msg.RevealAgent) {
        resolveAgentId(m)?.let { revealAgent(it) } ?: panel.transcript.showTranscript(null)
    }

    private fun revealAgent(agentId: String) {
        if (hiddenAgents.remove(agentId)) {
            session.sessionId?.let { PluginAgentIndex.getInstance(project).setTabOpen(it, agentId, true) }
            render()
        }
        // The bar opens the path down to it; the page owns which levels are shown (see app-tabs.js).
        panel.host.exec(
            "window.cc.revealAgentTab && window.cc.revealAgentTab(" + JcefBridge.jsString(agentId) + ")",
        )
        panel.transcript.showTranscript(agentId)
    }

    /** Closing hides a view: the agent, its transcript and its place in the tree all stay exactly as they are. */
    fun closeAgent(agentId: String) {
        hiddenAgents += agentId
        session.sessionId?.let { PluginAgentIndex.getInstance(project).setTabOpen(it, agentId, false) }
        panel.transcript.showTranscript(null)
        render()
    }

    /**
     * Turns the open subtab into a chat tab of its own.
     *
     * The tab shows the SAME session — an agent is not a separate conversation, it is part of this one — but
     * it is pinned to that agent's (or that task's) transcript and stays there while you use the chat next to
     * it. Which is the whole point: a subtab is a view of this browser, so it disappears the moment you look
     * at something else, and the one agent you keep coming back to deserves better than being re-found.
     */
    fun pinSubtab(m: JcefBridge.Msg.PinSubtab) {
        val strip = panel.chatStrip() ?: return
        val agentId = m.agentId.takeIf { it.isNotBlank() }
        val taskId = m.taskId.takeIf { it.isNotBlank() }
        if (agentId == null && taskId == null) return
        val node = agentId?.let { session.runningAgents.nodes[it] }
        val title = when {
            node != null -> "${node.kindLabel} (${node.meta.label()})"

            taskId != null -> session.backgroundTaskRegistry.all.firstOrNull { it.taskId == taskId }
                ?.let { "Background Task (${it.label()})" } ?: "Background Task"

            else -> "Agent"
        }
        strip.pin(JcefChatPanel(project, session), agentId, taskId, title)
    }

    /**
     * The agent a `revealAgent` names: its id when the sender had one, else the agent spawned by that
     * `tool_use_id`. Null when nothing matches — a card whose agent the binary never wrote a sidecar for
     * (or one belonging to a terminal run) simply does nothing, rather than opening someone else's tab.
     */
    private fun resolveAgentId(m: JcefBridge.Msg.RevealAgent): String? {
        m.agentId.takeIf { it.isNotBlank() }?.let { return it }
        val tool = m.toolUseId.takeIf { it.isNotBlank() } ?: return null
        return session.runningAgents.nodes.values.firstOrNull { it.meta.toolUseId == tool }?.agentId
    }
}
