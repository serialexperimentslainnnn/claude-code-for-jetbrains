package dev.lain.claudejb.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import dev.lain.claudejb.session.AttentionReason
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.SessionListener
import dev.lain.claudejb.settings.ClaudeSettings
import javax.swing.JComponent

class ClaudeToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val manager = ChatSessionManager.getInstance(project)
        val cm = toolWindow.contentManager

        val tabs = ChatTabsPanel()
        tabs.onEvents(
            selected = { tab -> tab?.session?.let { manager.setActive(it) } },
            closed = { tab -> tab.session?.let { manager.remove(it) } },
        )
        val content = ContentFactory.getInstance().createContent(tabs, "", false)
        content.isCloseable = false
        content.setPreferredFocusedComponent { tabs.selectedChat?.focusTarget() }
        content.setDisposer(tabs)
        cm.addContent(content)

        val commands = TabSessionCommands(project, tabs) { session, select -> openChat(project, tabs, session, select) }
        tabs.commands = commands
        commands.restoreOrCreate()

        toolWindow.setAdditionalGearActions(buildGearGroup(project, tabs, commands))
    }

    private fun openChat(project: Project, tabs: ChatTabsPanel, session: ClaudeSession, select: Boolean = true) {
        ClaudeSettings.getInstance(project).applyTo(session)
        session.start()

        val panel = JcefChatPanel(project, session)
        val tab = tabs.add(panel, tabTitle(session.title), session.title, panel)
        session.addListener(object : SessionListener {
            override fun onAttention(reason: AttentionReason) = onSessionAttention(project, tabs, session, reason)
            override fun onTitleChanged() {
                tabs.tabFor(session)?.let { tabs.relabel(it, tabTitle(session.title), session.title) }
            }
        })
        if (select) {
            panel.host.whenWebReady {
                if (tabs.all().lastOrNull() === tab) tabs.select(tab)
            }
        }
    }

    private fun onSessionAttention(project: Project, tabs: ChatTabsPanel, session: ClaudeSession, reason: AttentionReason) {
        val tw = resolveToolWindow(project)
        val tab = tabs.tabFor(session) ?: return
        val onScreen = tw != null && tw.isVisible && tabs.selected === tab
        if (onScreen) return

        tabs.badge(tab, true)

        val now = System.currentTimeMillis()
        if (now - tab.lastNotified <= NOTIFY_THROTTLE_MS) return
        tab.lastNotified = now

        val text = when (reason) {
            AttentionReason.PERMISSION -> "Claude needs your approval in \"${session.title}\"."
            AttentionReason.TURN_DONE -> "Claude finished responding in \"${session.title}\"."
            AttentionReason.ERROR -> "Claude hit an error in \"${session.title}\"."
        }
        NotificationGroupManager.getInstance().getNotificationGroup("Claude Code")
            .createNotification(
                "Claude Code",
                text,
                if (reason == AttentionReason.ERROR) NotificationType.ERROR else NotificationType.INFORMATION,
            )
            .addAction(
                NotificationAction.createSimpleExpiring("Open") {
                    tabs.tabFor(session)?.let { tabs.select(it) }
                    resolveToolWindow(project)?.activate(null)
                },
            )
            .notify(project)
    }

    private fun resolveToolWindow(project: Project): ToolWindow? =
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)

    private fun activePanel(tabs: ChatTabsPanel): JcefChatPanel? = tabs.selectedChat

    private fun buildGearGroup(project: Project, tabs: ChatTabsPanel, commands: TabSessionCommands) =
        DefaultActionGroup().apply {
            add(simple("Session Info (Context · Cost · Account · MCP)…") { activePanel(tabs)?.openDashboard() })
            add(simple("Agents") { activePanel(tabs)?.let { InfoDialogs.showAgents(project, it.session) } })
            add(SessionDiffAction(project, tabs))
            add(simple("Binary Version…") { activePanel(tabs)?.let { InfoDialogs.showBinaryVersion(project, it.session) } })
            add(simple("Effective Settings…") { activePanel(tabs)?.let { InfoDialogs.showEffectiveSettings(project, it.session) } })
            addSeparator()
            add(simple("Rename Session…") { commands.renameActiveSession() })
            add(simple("Fork Session") { commands.forkActiveSession() })
            add(simple("Open Previous Session…") { commands.openPreviousSession() })
            add(simple("Add Current File as @-context") { activePanel(tabs)?.mentionCurrentFile() })
            addSeparator()
            addAll(GitContextActions.gearEntries(project))
            addAll(
                GitPromptedActions.gearEntries(project) {
                    activePanel(project)?.gitChat?.session()
                        ?: ChatSessionManager.getInstance(project).gitChatOrCreate()
                },
            )
            add(GitIdeMenu.gearEntry())
            addSeparator()
            add(
                simple("Settings…") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, ClaudeSettingsConfigurable::class.java)
                },
            )
        }

    private fun tabTitle(title: String): String =
        TabSessionCommands.truncate(title.trim().ifBlank { "Chat" }, TAB_TITLE_MAX)

    private fun simple(text: String, action: () -> Unit): AnAction = object : AnAction(text) {
        override fun actionPerformed(e: AnActionEvent) = action()
    }

    companion object {
        const val TOOL_WINDOW_ID = "Claude Code"

        private fun tabsPanel(project: Project): ChatTabsPanel? {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return null
            return toolWindow.contentManager.contents.firstNotNullOfOrNull { it.component as? ChatTabsPanel }
        }

        fun activePanel(project: Project): JcefChatPanel? = tabsPanel(project)?.selectedChat

        internal fun chatTabs(project: Project): ChatTabsPanel? = tabsPanel(project)

        fun contextComponent(project: Project): JComponent? = tabsPanel(project)

        fun newChat(project: Project) {
            tabsPanel(project)?.commands?.newChat()
        }

        fun showGitView(project: Project) {
            val panel = activePanel(project) ?: return
            panel.pushGit()
            panel.host.exec("window.cc.showGitView && window.cc.showGitView()")
        }

        const val NOTIFY_THROTTLE_MS = 3000L

        const val TAB_TITLE_MAX = 22
    }
}
