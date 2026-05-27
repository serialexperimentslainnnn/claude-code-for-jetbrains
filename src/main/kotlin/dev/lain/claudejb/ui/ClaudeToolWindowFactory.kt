package dev.lain.claudejb.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
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
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import dev.lain.claudejb.session.AttentionReason
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.EntryDTO
import dev.lain.claudejb.session.SessionHistory
import dev.lain.claudejb.session.SessionListener
import dev.lain.claudejb.session.SessionRef
import dev.lain.claudejb.session.SessionStore
import dev.lain.claudejb.session.SessionTitleReader
import dev.lain.claudejb.session.SessionTranscriptReader
import dev.lain.claudejb.settings.ClaudeSettings
import javax.swing.JList

/**
 * Registers the right-anchored "Claude Code" tool window. Each conversation is a closeable tab (a
 * [ChatPanel] over its own [ClaudeSession]); "New chat" opens another, mirroring the web UI. The title
 * bar and gear menu act on whichever tab is selected.
 */
class ClaudeToolWindowFactory : ToolWindowFactory, DumbAware {

    /** Maps each live session to its tab, so a background session can target its own badge/notification. */
    private val contents = HashMap<ClaudeSession, Content>()
    /** Per-session throttle for attention notifications (badge is never throttled). */
    private val lastNotified = HashMap<ClaudeSession, Long>()
    private var toolWindow: ToolWindow? = null

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        this.toolWindow = toolWindow
        val manager = ChatSessionManager.getInstance(project)
        val cm = toolWindow.contentManager

        cm.addContentManagerListener(object : ContentManagerListener {
            override fun contentRemoved(event: ContentManagerEvent) {
                (event.content.component as? ChatPanel)?.let {
                    manager.remove(it.session)
                    contents.remove(it.session)
                    lastNotified.remove(it.session)
                }
            }

            override fun selectionChanged(event: ContentManagerEvent) {
                if (event.operation == ContentManagerEvent.ContentOperation.add) {
                    // The user is now looking at this tab — clear its attention badge.
                    event.content.setIcon(null)
                    (event.content.component as? ChatPanel)?.let { manager.setActive(it.session) }
                }
            }
        })

        restoreOrCreate(project, cm, manager)

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
        contents[session] = content
        session.addListener(object : SessionListener {
            override fun onAttention(reason: AttentionReason) = onSessionAttention(project, cm, session, reason)
            override fun onTitleChanged() { contents[session]?.displayName = session.title }
        })
        cm.addContent(content)
        cm.setSelectedContent(content)
        ClaudeSettings.getInstance(project).applyTo(session)
        session.start()
        panel.focusInput()
    }

    /**
     * A background session asked for attention. Suppress everything when this session's tab is the one on
     * screen — the tool window is visible and this tab is selected — regardless of where keyboard/mouse focus
     * currently is (working in the editor must NOT trigger a popup for the chat you're already looking at).
     * Otherwise badge the tab (always) and raise a throttled notification. Fired on the EDT.
     */
    private fun onSessionAttention(project: Project, cm: ContentManager, session: ClaudeSession, reason: AttentionReason) {
        val tw = toolWindow
        val content = contents[session] ?: return
        val onScreen = tw != null && tw.isVisible && cm.selectedContent?.component === content.component
        if (onScreen) return

        content.setIcon(AllIcons.General.Modified)

        val now = System.currentTimeMillis()
        if (now - (lastNotified[session] ?: 0L) <= NOTIFY_THROTTLE_MS) return
        lastNotified[session] = now

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
            .addAction(NotificationAction.createSimpleExpiring("Open") {
                contents[session]?.let { cm.setSelectedContent(it); it.setIcon(null) }
                toolWindow?.activate(null)
            })
            .notify(project)
    }

    private fun activePanel(cm: ContentManager): ChatPanel? = cm.selectedContent?.component as? ChatPanel

    private fun buildGearGroup(project: Project, cm: ContentManager) =
        DefaultActionGroup().apply {
            add(simple("Context Usage") { activePanel(cm)?.let { InfoDialogs.showContextUsage(project, it.session) } })
            add(simple("Session Cost") { activePanel(cm)?.let { InfoDialogs.showSessionCost(project, it.session) } })
            add(simple("MCP Servers") { activePanel(cm)?.let { InfoDialogs.showMcpStatus(project, it.session) } })
            add(simple("Agents") { activePanel(cm)?.let { InfoDialogs.showAgents(project, it.session) } })
            addSeparator()
            add(simple("Open Previous Session…") { openPreviousSession(project, cm) })
            add(simple("Add Current File as @-context") { activePanel(cm)?.mentionCurrentFile() })
            add(simple("Settings…") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, ClaudeSettingsConfigurable::class.java)
            })
        }

    /** A restorable tab: the binary session id, its resolved title and the transcript read back from the session file. */
    private data class RestoredSession(val id: String, val title: String?, val entries: List<EntryDTO>)

    /**
     * On startup, reopens the tabs that were open last time (in their stored order) by re-reading each transcript
     * from the binary's session file (the source of truth) and re-attaching via `--resume`. When no open tabs were
     * recorded (e.g. first run after install), it falls back to the most recent session for the project, so the
     * default is always "resume your last conversation" rather than an empty chat. Ids whose session file no longer
     * exists are skipped; only if there's genuinely nothing to restore does a fresh chat open. Restore can be turned
     * off in settings. The blocking session-file reads run on a pooled thread; tabs are opened back on the EDT.
     */
    private fun restoreOrCreate(project: Project, cm: ContentManager, manager: ChatSessionManager) {
        if (!ClaudeSettings.getInstance(project).restoreOpenChatsOnStartup) {
            openChat(project, cm, manager.create())
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val ids = SessionHistory.getInstance(project).openSessions()
                .filter { SessionStore.exists(it) }
                .ifEmpty { listOfNotNull(SessionTranscriptReader.listSessions(project).firstOrNull()?.sessionId) }
            val restored = ids
                .map { RestoredSession(it, SessionTitleReader.readTitle(it), SessionTranscriptReader.readEntries(it)) }
            ApplicationManager.getApplication().invokeLater({
                if (restored.isEmpty()) {
                    openChat(project, cm, manager.create())
                } else {
                    for (r in restored) {
                        val s = manager.create()
                        s.title = r.title ?: s.title
                        s.restore(r.id, r.entries)
                        openChat(project, cm, s)
                    }
                }
            }, ModalityState.any())
        }
    }

    /**
     * Lets the user reopen a past chat: lists the binary's sessions (title + relative time, read from the session
     * files), and on pick creates a fresh session, restores its transcript + sessionId, then opens the tab. Because
     * [openChat] calls `session.start()` whose `resume` default keys off `sessionId != null`, the restored id makes
     * the binary re-attach via `--resume` automatically. The blocking session-file reads run on a pooled thread;
     * the popup and tab opening happen on the EDT.
     */
    private fun openPreviousSession(project: Project, cm: ContentManager) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val refs = SessionTranscriptReader.listSessions(project)
            ApplicationManager.getApplication().invokeLater({
                if (refs.isEmpty()) {
                    Messages.showInfoMessage(project, "No previous sessions have been saved yet.", "Claude Code")
                    return@invokeLater
                }
                JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(refs)
                    .setTitle("Open Previous Session")
                    .setRenderer(object : SimpleListCellRenderer<SessionRef>() {
                        override fun customize(
                            list: JList<out SessionRef>, value: SessionRef?, index: Int, selected: Boolean, hasFocus: Boolean,
                        ) {
                            value ?: return
                            text = "${value.title}  —  ${relativeTime(value.lastModified)}"
                        }
                    })
                    .setItemChosenCallback { ref ->
                        // Re-read the transcript off-EDT, then build/open the tab on the EDT.
                        ApplicationManager.getApplication().executeOnPooledThread {
                            val entries = SessionTranscriptReader.readEntries(ref.sessionId)
                            ApplicationManager.getApplication().invokeLater({
                                val manager = ChatSessionManager.getInstance(project)
                                val s = manager.create()
                                s.title = ref.title
                                s.restore(ref.sessionId, entries)
                                openChat(project, cm, s)
                            }, ModalityState.any())
                        }
                    }
                    .setRequestFocus(true)
                    .createPopup()
                    .showCenteredInCurrentWindow(project)
            }, ModalityState.any())
        }
    }

    /** Coarse, human-friendly elapsed-time label for an epoch-millis timestamp. */
    private fun relativeTime(timestamp: Long): String {
        val secs = (System.currentTimeMillis() - timestamp).coerceAtLeast(0) / 1000
        return when {
            secs < 60 -> "just now"
            secs < 3600 -> "${secs / 60}m ago"
            secs < 86400 -> "${secs / 3600}h ago"
            else -> "${secs / 86400}d ago"
        }
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

    private companion object {
        /** Min gap between attention notifications for the same session, to avoid spam. */
        const val NOTIFY_THROTTLE_MS = 3000L
    }
}
