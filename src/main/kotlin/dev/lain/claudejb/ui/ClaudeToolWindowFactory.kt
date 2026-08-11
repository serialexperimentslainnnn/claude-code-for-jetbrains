package dev.lain.claudejb.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.content.ContentFactory
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
 * [JcefChatPanel] over its own [ClaudeSession]); "New chat" opens another, mirroring the web UI. The title
 * bar and gear menu act on whichever tab is selected.
 *
 * The tab strip is the plugin's own ([ChatTabsPanel]) rather than the tool window's: the platform's content
 * tabs do not scroll, so past a handful of chats the earliest ones simply stopped being drawn. The tool
 * window therefore holds exactly ONE content, and everything below talks to that strip.
 */
class ClaudeToolWindowFactory : ToolWindowFactory, DumbAware {

    // NB: a ToolWindowFactory is an APPLICATION-level extension — one instance serves every open project. These
    // maps are keyed by ClaudeSession (unique per session across projects), so they never collide between
    // projects. The tool window, however, must be resolved per-project on demand ([resolveToolWindow]) — caching
    // it in a field would make a second project's window overwrite the first's and misdirect attention checks.
    /** Maps each live session to its tab, so a background session can target its own badge/notification. */
    private val tabOf = HashMap<ClaudeSession, ChatTabsPanel.ChatTab>()

    /** Per-session throttle for attention notifications (badge is never throttled). */
    private val lastNotified = HashMap<ClaudeSession, Long>()

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val manager = ChatSessionManager.getInstance(project)
        val cm = toolWindow.contentManager

        val tabs = ChatTabsPanel()
        tabs.onEvents(
            selected = { tab -> (tab?.component as? JcefChatPanel)?.let { manager.setActive(it.session) } },
            closed = { tab ->
                (tab.component as? JcefChatPanel)?.let {
                    manager.remove(it.session)
                    tabOf.remove(it.session)
                    lastNotified.remove(it.session)
                }
            },
        )
        // ONE content, holding the whole strip. Not closeable: closing it would take every chat with it, and
        // the tool window would be left showing nothing with no way back.
        val content = ContentFactory.getInstance().createContent(tabs, "", false)
        content.isCloseable = false
        // The focus fix, unchanged in substance: tell the platform where this content's keyboard focus lives,
        // resolved LAZILY against the selected tab (CEF's real input component does not exist yet, and the
        // selected tab changes underneath).
        content.setPreferredFocusedComponent { tabs.selectedChat?.focusTarget() }
        content.setDisposer(tabs)
        cm.addContent(content)

        restoreOrCreate(project, tabs, manager)

        toolWindow.setTitleActions(
            listOf(
                SignOutAction(tabs),
                NewChatAction { openChat(project, tabs, manager.create()) },
                InterruptAction(tabs),
                CommandsAction(tabs),
                DiffHistoryAction { openDiffHistory(project, tabs) },
                CloseAllDiffsAction(project),
            ),
        )
        toolWindow.setAdditionalGearActions(buildGearGroup(project, tabs))
    }

    /** Starts [session]'s process, then adds a tab for it and wires it. */
    private fun openChat(project: Project, tabs: ChatTabsPanel, session: ClaudeSession) {
        // Launch the binary FIRST, before building the tab. `start()` only dispatches — it hands the blocking
        // work (env resolution sources a login shell, then the spawn) to a pooled thread and returns — so doing
        // it here means `claude` boots WHILE JCEF creates its browser, instead of waiting for it to finish.
        // Constructing the panel is not free, and it used to be entirely in front of the launch.
        //
        // Nothing is lost by having no listener attached yet: the panel's constructor pushes the full state and
        // marks the transcript structural, so anything that landed in the gap is sent on its first frame, and
        // `whenReady` runs its deferred requests immediately if the session is already up by then.
        ClaudeSettings.getInstance(project).applyTo(session)
        session.start()

        val panel = JcefChatPanel(project, session)
        // The panel is the tab's disposer — same contract the Content had, and what makes a closed chat
        // actually tear its JCEF browser down instead of leaking it.
        val tab = tabs.add(panel, tabTitle(session.title), session.title, panel)
        tabOf[session] = tab
        session.addListener(object : SessionListener {
            override fun onAttention(reason: AttentionReason) = onSessionAttention(project, tabs, session, reason)
            override fun onTitleChanged() {
                tabOf[session]?.let { tabs.relabel(it, tabTitle(session.title), session.title) }
            }
        })
        // Selecting transfers the keyboard focus as part of the selection — the same path a manual tab switch
        // takes ([ChatTabsPanel]'s selection listener). Selecting and then asking for the focus separately
        // loses the race: "New chat" is a toolbar action, and the platform restores focus to wherever it was
        // when an action finishes. (The caret itself is settled later, when the page is up — JcefHost.markWebReady.)
        tabs.select(tab)
    }

    /**
     * A background session asked for attention. Suppress everything when this session's tab is the one on
     * screen — the tool window is visible and this tab is selected — regardless of where keyboard/mouse focus
     * currently is (working in the editor must NOT trigger a popup for the chat you're already looking at).
     * Otherwise badge the tab (always) and raise a throttled notification. Fired on the EDT.
     */
    private fun onSessionAttention(project: Project, tabs: ChatTabsPanel, session: ClaudeSession, reason: AttentionReason) {
        val tw = resolveToolWindow(project)
        val tab = tabOf[session] ?: return
        val onScreen = tw != null && tw.isVisible && tabs.selected === tab
        if (onScreen) return

        // A flag, not an icon: the bar is drawn by the page now, which shows it as a dot on the chat's pill.
        tabs.badge(tab, true)

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
            .addAction(
                NotificationAction.createSimpleExpiring("Open") {
                    tabOf[session]?.let { tabs.select(it) } // selecting clears the badge, see ChatTabsPanel
                    resolveToolWindow(project)?.activate(null)
                },
            )
            .notify(project)
    }

    /** Resolves this project's Claude Code tool window on demand (the factory caches no per-project window). */
    private fun resolveToolWindow(project: Project): ToolWindow? =
        ToolWindowManager.getInstance(project).getToolWindow("Claude Code")

    private fun activePanel(tabs: ChatTabsPanel): JcefChatPanel? = tabs.selectedChat

    /**
     * Opens (or focuses) the Diff History tab for the active session. If a [DiffHistoryPanel] for that same session
     * is already open we just [DiffHistoryPanel.refresh] it and re-select its tab, so it always reflects edits made
     * since it was surfaced; otherwise a fresh closeable tab is created. No-op (with a hint) when no chat is open.
     */
    private fun openDiffHistory(project: Project, tabs: ChatTabsPanel) {
        val session = activePanel(tabs)?.session ?: run {
            Messages.showInfoMessage(project, "Open a chat first.", "Diff History")
            return
        }
        val existing = tabs.all().firstOrNull {
            (it.component as? DiffHistoryPanel)?.boundSession === session
        }
        if (existing != null) {
            (existing.component as DiffHistoryPanel).refresh()
            tabs.select(existing)
            return
        }
        val panel = DiffHistoryPanel(project, session)
        tabs.select(tabs.add(panel, "Diff History", "Diff History", null))
    }

    private fun buildGearGroup(project: Project, tabs: ChatTabsPanel) =
        DefaultActionGroup().apply {
            // Context · Cost · Account · MCP all live in the formatted JCEF dashboard now — open that
            // instead of the old plain-text dialogs.
            add(simple("Session Info (Context · Cost · Account · MCP)…") { activePanel(tabs)?.openDashboard() })
            add(simple("Agents") { activePanel(tabs)?.let { InfoDialogs.showAgents(project, it.session) } })
            add(simple("Binary Version…") { activePanel(tabs)?.let { InfoDialogs.showBinaryVersion(project, it.session) } })
            add(simple("Effective Settings…") { activePanel(tabs)?.let { InfoDialogs.showEffectiveSettings(project, it.session) } })
            addSeparator()
            add(simple("Rename Session…") { renameActiveSession(project, tabs) })
            add(simple("Fork Session") { forkActiveSession(project, tabs) })
            // No "Delete Previous Session…": the plugin does not delete the user's conversations. See
            // SessionStore's KDoc and NoFileDeletionContractTest.
            add(simple("Open Previous Session…") { openPreviousSession(project, tabs) })
            add(simple("Add Current File as @-context") { activePanel(tabs)?.mentionCurrentFile() })
            add(
                simple("Settings…") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, ClaudeSettingsConfigurable::class.java)
                },
            )
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
    private fun restoreOrCreate(project: Project, tabs: ChatTabsPanel, manager: ChatSessionManager) {
        if (!ClaudeSettings.getInstance(project).restoreOpenChatsOnStartup) {
            openChat(project, tabs, manager.create())
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val ids = SessionHistory.getInstance(project).openSessions()
                .filter { SessionStore.exists(it) }
                .ifEmpty { listOfNotNull(SessionTranscriptReader.listSessions(project).firstOrNull()?.sessionId) }
            val restored = ids
                .map {
                    RestoredSession(
                        it,
                        SessionTitleReader.readTitle(it),
                        SessionTranscriptReader.readEntries(
                            it,
                            SessionTranscriptReader.DEFAULT_RESTORE_CAP,
                            project.basePath,
                        ),
                    )
                }
            ApplicationManager.getApplication().invokeLater({
                if (restored.isEmpty()) {
                    openChat(project, tabs, manager.create())
                } else {
                    for (r in restored) {
                        val s = manager.create()
                        s.title = r.title ?: s.title
                        s.restore(r.id, r.entries)
                        openChat(project, tabs, s)
                    }
                }
            }, ModalityState.any())
        }
    }

    /**
     * Renames the active session: prompts for a new title and hands it to [ClaudeSession.renameSession], which drives
     * the binary's `/rename` (persisting a `customTitle` line) and relabels the tab via the title-changed listener.
     * No-op when there's no active chat or the input is blank/unchanged.
     */
    private fun renameActiveSession(project: Project, tabs: ChatTabsPanel) {
        val session = activePanel(tabs)?.session ?: return
        val input = Messages.showInputDialog(
            project,
            "New session name:",
            "Rename Session",
            null,
            session.title,
            null,
        )?.trim().orEmpty()
        if (input.isEmpty() || input == session.title) return
        session.renameSession(input)
    }

    /**
     * Forks the active session into a new tab: re-reads the source transcript from the binary's session file (the
     * source of truth) and restores it into a freshly-created [ClaudeSession], then opens it. Per the E5 spec the
     * fork reuses the source `sessionId`, so [openChat]'s `start()` re-attaches via `--resume`; the binary branches
     * the conversation once the new tab sends its first message. No-op when there's no active session id yet.
     */
    private fun forkActiveSession(project: Project, tabs: ChatTabsPanel) {
        val source = activePanel(tabs)?.session ?: return
        val sourceId = source.sessionId ?: run {
            Messages.showInfoMessage(project, "This session hasn't been initialized yet — nothing to fork.", "Claude Code")
            return
        }
        val sourceTitle = source.title
        ApplicationManager.getApplication().executeOnPooledThread {
            val entries = SessionTranscriptReader.readEntries(
                sourceId,
                SessionTranscriptReader.DEFAULT_RESTORE_CAP,
                project.basePath,
            )
            ApplicationManager.getApplication().invokeLater({
                val manager = ChatSessionManager.getInstance(project)
                val s = manager.create()
                s.title = "$sourceTitle (fork)"
                s.restore(sourceId, entries)
                openChat(project, tabs, s)
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
    private fun openPreviousSession(project: Project, tabs: ChatTabsPanel) {
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
                    .setRenderer(SessionRefRenderer())
                    .setItemChosenCallback { ref ->
                        // Re-read the transcript off-EDT, then build/open the tab on the EDT.
                        ApplicationManager.getApplication().executeOnPooledThread {
                            val entries = SessionTranscriptReader.readEntries(
                                ref.sessionId,
                                SessionTranscriptReader.DEFAULT_RESTORE_CAP,
                                project.basePath,
                            )
                            ApplicationManager.getApplication().invokeLater({
                                val manager = ChatSessionManager.getInstance(project)
                                val s = manager.create()
                                s.title = ref.title
                                s.restore(ref.sessionId, entries)
                                openChat(project, tabs, s)
                            }, ModalityState.any())
                        }
                    }
                    .setRequestFocus(true)
                    .createPopup()
                    .showCenteredInCurrentWindow(project)
            }, ModalityState.any())
        }
    }

    /**
     * Two-line renderer for a past session: the title with relative time on the first line, and best-effort metadata
     * (git branch · absolute creation date · the first prompt, truncated) on a dimmed second line. Built as HTML so a
     * single [JList] cell can show both lines; missing metadata fields are simply omitted.
     */
    private inner class SessionRefRenderer : SimpleListCellRenderer<SessionRef>() {
        override fun customize(
            list: JList<out SessionRef>,
            value: SessionRef?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            value ?: return
            val parts = buildList {
                value.gitBranch?.let { add(escapeHtml(it)) }
                value.createdAt?.let { add(escapeHtml(formatCreatedAt(it))) }
                value.firstPrompt?.let { add(escapeHtml(truncate(it.replace('\n', ' '), PROMPT_PREVIEW_MAX))) }
            }
            val sub = if (parts.isEmpty()) {
                ""
            } else {
                "<br><font color='#888888'>${parts.joinToString("  ·  ")}</font>"
            }
            text = "<html>${escapeHtml(value.title)}  —  ${relativeTime(value.lastModified)}$sub</html>"
        }
    }

    /** Renders the binary's ISO-8601 createdAt as a short local date, falling back to the raw string. */
    private fun formatCreatedAt(iso: String): String =
        runCatching { java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() }
            .getOrDefault(iso)

    private fun truncate(s: String, max: Int): String = if (s.length <= max) s else s.take(max - 1) + "…"

    /** Keeps tab labels short so many open chats don't push the tab strip off-screen; full title lives in the tooltip. */
    private fun tabTitle(title: String): String = truncate(title.trim().ifBlank { "Chat" }, TAB_TITLE_MAX)

    /** Minimal HTML escaping so user-supplied titles/prompts can't break the cell's HTML rendering. */
    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Coarse, human-friendly elapsed-time label for an epoch-millis timestamp. */
    private fun relativeTime(timestamp: Long): String {
        val secs = (System.currentTimeMillis() - timestamp).coerceAtLeast(0) / MILLIS_PER_SECOND
        return when {
            secs < SECONDS_PER_MINUTE -> "just now"
            secs < SECONDS_PER_HOUR -> "${secs / SECONDS_PER_MINUTE}m ago"
            secs < SECONDS_PER_DAY -> "${secs / SECONDS_PER_HOUR}h ago"
            else -> "${secs / SECONDS_PER_DAY}d ago"
        }
    }

    private fun simple(text: String, action: () -> Unit): AnAction = object : AnAction(text) {
        override fun actionPerformed(e: AnActionEvent) = action()
    }

    private class NewChatAction(private val onNew: () -> Unit) :
        AnAction("New Chat", "Start a fresh conversation in a new tab", AllIcons.General.Add) {
        override fun actionPerformed(e: AnActionEvent) = onNew()
    }

    /**
     * Signs out of Claude, from the tool window's own title bar.
     *
     * It lives here rather than in the web UI because the composer's readout is a wrapping flex row of
     * metrics: a button at its end drops onto a second line as soon as the numbers fill the width. The title
     * bar is on screen at all times, never reflows, and is where an IDE user looks for a tool window's own
     * controls. The dashboard's account row keeps its Log out — same message, two doors.
     */
    private class SignOutAction(private val tabs: ChatTabsPanel) :
        AnAction("Log out", "Sign out of Claude — stops the session and returns to the sign-in card", AllIcons.Actions.Exit) {
        override fun actionPerformed(e: AnActionEvent) {
            tabs.selectedChat?.requestLogout()
        }

        override fun update(e: AnActionEvent) {
            // Greyed on a non-chat tab (Diff History), where there is no session to sign out of.
            e.presentation.isEnabled = tabs.selectedChat != null
        }

        /** EDT, for the same selection-state data race spelled out in [InterruptAction]. */
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private class InterruptAction(private val tabs: ChatTabsPanel) :
        AnAction("Interrupt", "Stop the current turn", AllIcons.Actions.Suspend) {
        private fun session(): ClaudeSession? = tabs.selectedChat?.session
        override fun actionPerformed(e: AnActionEvent) {
            session()?.interrupt()
        }
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = session()?.turnActive == true
        }

        /**
         * EDT **deliberately**, not an oversight. [session] reads the tab strip's current selection, which is
         * Swing state mutated on the EDT with no internal synchronization and no threading assertion to warn
         * you. Reading it from a background thread is a data race whose worst case is not a stale label but an
         * exception mid-iteration. Moving this to BGT to silence an "N ms to grab EDT" warning would trade a
         * cosmetic log line for a real (if rare) crash.
         */
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private class CommandsAction(private val tabs: ChatTabsPanel) :
        AnAction("Commands", "Browse all slash commands", AllIcons.Actions.Find) {
        override fun actionPerformed(e: AnActionEvent) {
            tabs.selectedChat?.showCommandPalette()
        }
    }

    /** Opens (or focuses) the Diff History tab for the active session — every reviewable edit, with revert. */
    private class DiffHistoryAction(private val onOpen: () -> Unit) :
        AnAction("Diff History", "Review and revert Claude's edits in this session", AllIcons.Vcs.History) {
        override fun actionPerformed(e: AnActionEvent) = onOpen()
    }

    /** Closes every diff tab the plugin opened (auto-approved and manually reviewed). Greyed when none open. */
    private class CloseAllDiffsAction(private val project: Project) :
        AnAction("Close All Diffs", "Close every diff tab Claude has opened", AllIcons.Actions.GC) {
        override fun actionPerformed(e: AnActionEvent) {
            dev.lain.claudejb.diff.OpenedDiffsService.getInstance(project).closeAll()
        }
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = dev.lain.claudejb.diff.OpenedDiffsService.getInstance(project).openCount() > 0
        }

        /**
         * BGT: the only thing read here is the size of a `CopyOnWriteArraySet` in a project service — no Swing,
         * no PSI, no editor. Keeping it on the EDT put this action in the queue behind everything else the IDE
         * does at startup, which is how it ended up in the log as "N ms to grab EDT".
         */
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }

    companion object {
        /** Min gap between attention notifications for the same session, to avoid spam. */
        const val NOTIFY_THROTTLE_MS = 3000L

        /** Max characters in a chat tab label before it's ellipsized (full title stays in the tooltip). */
        const val TAB_TITLE_MAX = 22

        /** Max characters of the session's first prompt shown as the subtitle in the "Open Previous Session" list. */
        private const val PROMPT_PREVIEW_MAX = 60

        // Units for [relativeTime]'s "5m ago" / "2h ago" / "3d ago" bucketing.
        private const val MILLIS_PER_SECOND = 1000
        private const val SECONDS_PER_MINUTE = 60
        private const val SECONDS_PER_HOUR = 3600
        private const val SECONDS_PER_DAY = 86_400

        /**
         * Opens (or focuses) the Diff History / rollback tab for [session] in the Claude Code tool window.
         * Callable from anywhere (e.g. the JCEF composer's history button), not just the toolbar action.
         */
        fun openDiffHistoryFor(project: Project, session: ClaudeSession) {
            val tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Claude Code") ?: return
            // The tool window holds ONE content: the tab strip. Everything the user sees is a tab inside it.
            val tabs = tw.contentManager.contents.firstNotNullOfOrNull { it.component as? ChatTabsPanel } ?: return
            val existing = tabs.all().firstOrNull {
                (it.component as? DiffHistoryPanel)?.boundSession === session
            }
            if (existing != null) {
                (existing.component as DiffHistoryPanel).refresh()
                tabs.select(existing)
            } else {
                val panel = DiffHistoryPanel(project, session)
                tabs.select(tabs.add(panel, "Diff History", "Diff History", null))
            }
            tw.activate(null)
        }
    }
}
