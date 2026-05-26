package dev.lain.claudejb.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.settings.ClaudeSettings

/**
 * Registers the right-anchored "Claude Code" tool window. Each conversation is a closeable tab (a
 * [ChatPanel] over its own [ClaudeSession]); "New chat" opens another, mirroring the web UI. The title
 * bar and gear menu act on whichever tab is selected.
 */
class ClaudeToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val manager = ChatSessionManager.getInstance(project)
        val cm = toolWindow.contentManager

        cm.addContentManagerListener(object : ContentManagerListener {
            override fun contentRemoved(event: ContentManagerEvent) {
                (event.content.component as? ChatPanel)?.let { manager.remove(it.session) }
            }

            override fun selectionChanged(event: ContentManagerEvent) {
                if (event.operation == ContentManagerEvent.ContentOperation.add) {
                    (event.content.component as? ChatPanel)?.let { manager.setActive(it.session) }
                }
            }
        })

        openChat(project, cm, manager.create())

        toolWindow.setTitleActions(
            listOf(
                NewChatAction { openChat(project, cm, manager.create()) },
                InterruptAction(cm),
                CommandsAction(cm),
            )
        )
        toolWindow.setAdditionalGearActions(buildGearGroup(project, cm))
    }

    /** Adds a tab for [session], wires it, and starts its process. */
    private fun openChat(project: Project, cm: ContentManager, session: ClaudeSession) {
        val panel = ChatPanel(project, session)
        val content = ContentFactory.getInstance().createContent(panel, session.title, false)
        content.isCloseable = true
        content.setDisposer(panel)
        cm.addContent(content)
        cm.setSelectedContent(content)
        ClaudeSettings.getInstance(project).applyTo(session)
        session.start()
        panel.focusInput()
    }

    private fun activePanel(cm: ContentManager): ChatPanel? = cm.selectedContent?.component as? ChatPanel

    private fun buildGearGroup(project: Project, cm: ContentManager) =
        DefaultActionGroup().apply {
            add(simple("Context Usage") { activePanel(cm)?.let { InfoDialogs.showContextUsage(project, it.session) } })
            add(simple("Session Cost") { activePanel(cm)?.let { InfoDialogs.showSessionCost(project, it.session) } })
            add(simple("MCP Servers") { activePanel(cm)?.let { InfoDialogs.showMcpStatus(project, it.session) } })
            add(simple("Agents") { activePanel(cm)?.let { InfoDialogs.showAgents(project, it.session) } })
            addSeparator()
            add(simple("Add Current File as @-context") { activePanel(cm)?.mentionCurrentFile() })
            add(simple("Settings…") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, ClaudeSettingsConfigurable::class.java)
            })
        }

    private fun simple(text: String, action: () -> Unit): AnAction = object : AnAction(text) {
        override fun actionPerformed(e: AnActionEvent) = action()
    }

    private class NewChatAction(private val onNew: () -> Unit) :
        AnAction("New Chat", "Start a fresh conversation in a new tab", AllIcons.General.Add) {
        override fun actionPerformed(e: AnActionEvent) = onNew()
    }

    private class InterruptAction(private val cm: ContentManager) :
        AnAction("Interrupt", "Stop the current turn", AllIcons.Actions.Suspend) {
        private fun session(): ClaudeSession? = (cm.selectedContent?.component as? ChatPanel)?.session
        override fun actionPerformed(e: AnActionEvent) {
            session()?.interrupt()
        }
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = session()?.turnActive == true
        }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private class CommandsAction(private val cm: ContentManager) :
        AnAction("Commands", "Browse all slash commands", AllIcons.Actions.Find) {
        override fun actionPerformed(e: AnActionEvent) {
            (cm.selectedContent?.component as? ChatPanel)?.showCommandPalette()
        }
    }
}
