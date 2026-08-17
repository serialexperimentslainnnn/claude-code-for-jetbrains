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

    // NB: a ToolWindowFactory is an APPLICATION-level extension — ONE instance serves every open project, for
    // the lifetime of the IDE. So this class holds NO state at all: everything per-project reaches it as a
    // parameter (the `project` and its `ChatTabsPanel`), the tool window is resolved on demand
    // ([resolveToolWindow]) rather than cached, and what used to be two `Map<ClaudeSession, …>` fields here now
    // lives on the tab (see [ChatTabsPanel.ChatTab.lastNotified] for why that mattered).

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val manager = ChatSessionManager.getInstance(project)
        val cm = toolWindow.contentManager

        val tabs = ChatTabsPanel()
        tabs.onEvents(
            selected = { tab -> tab?.session?.let { manager.setActive(it) } },
            // A PINNED tab is a second view of a chat, not a chat: closing it closes the view. Removing the
            // session here would dispose the `claude` process of the chat that spawned the agent — leaving its
            // own tab open over a dead session, and dropping it from the restorable set. The chat's own close
            // takes its pinned views with it (see ChatTabsPanel.close), so nothing is left behind either way.
            closed = { tab -> if (!tab.isPinnedView) tab.session?.let { manager.remove(it) } },
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

        // Restore/rename/fork/reopen are the user's CONVERSATIONS, not this tool window: they live in their
        // own collaborator and are handed the one thing only the factory knows — how to open a tab.
        val commands = TabSessionCommands(project, tabs) { openChat(project, tabs, it) }
        commands.restoreOrCreate()

        toolWindow.setTitleActions(
            listOf(
                SignOutAction(tabs),
                NewChatAction { openChat(project, tabs, manager.create()) },
                InterruptAction(tabs),
                CommandsAction(tabs),
                // The slot the Diff History button used to hold, and the integration's only visible door: the
                // gear entries hide themselves, so the one that matters on a project with no repository was
                // buried in a menu you had to already suspect. Reading Git stays in that menu, where it has
                // always been — the title bar has room for one Git button, and this is the one nobody can find.
                GitPromptedActions.toolbarAction(project) { commands.gitChat() },
                CloseAllDiffsAction(project),
            ),
        )
        toolWindow.setAdditionalGearActions(buildGearGroup(project, tabs, commands))
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
        session.addListener(object : SessionListener {
            override fun onAttention(reason: AttentionReason) = onSessionAttention(project, tabs, session, reason)
            override fun onTitleChanged() {
                tabs.tabFor(session)?.let { tabs.relabel(it, tabTitle(session.title), session.title) }
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
        val tab = tabs.tabFor(session) ?: return
        val onScreen = tw != null && tw.isVisible && tabs.selected === tab
        if (onScreen) return

        // A flag, not an icon: the bar is drawn by the page now, which shows it as a dot on the chat's pill.
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
                    tabs.tabFor(session)?.let { tabs.select(it) } // selecting clears the badge, see ChatTabsPanel
                    resolveToolWindow(project)?.activate(null)
                },
            )
            .notify(project)
    }

    /** Resolves this project's Claude Code tool window on demand (the factory caches no per-project window). */
    private fun resolveToolWindow(project: Project): ToolWindow? =
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)

    private fun activePanel(tabs: ChatTabsPanel): JcefChatPanel? = tabs.selectedChat

    private fun buildGearGroup(project: Project, tabs: ChatTabsPanel, commands: TabSessionCommands) =
        DefaultActionGroup().apply {
            // Context · Cost · Account · MCP all live in the formatted JCEF dashboard now — open that
            // instead of the old plain-text dialogs.
            add(simple("Session Info (Context · Cost · Account · MCP)…") { activePanel(tabs)?.openDashboard() })
            add(simple("Agents") { activePanel(tabs)?.let { InfoDialogs.showAgents(project, it.session) } })
            // The per-edit diffs answer "what is this call about to do". This one answers "what has this
            // whole session done to my tree", which is the question you ask before deciding to keep any of it.
            add(SessionDiffAction(project, tabs))
            add(simple("Binary Version…") { activePanel(tabs)?.let { InfoDialogs.showBinaryVersion(project, it.session) } })
            add(simple("Effective Settings…") { activePanel(tabs)?.let { InfoDialogs.showEffectiveSettings(project, it.session) } })
            addSeparator()
            add(simple("Rename Session…") { commands.renameActiveSession() })
            add(simple("Fork Session") { commands.forkActiveSession() })
            // No "Delete Previous Session…": the plugin does not delete the user's conversations. See
            // SessionStore's KDoc and NoFileDeletionContractTest.
            add(simple("Open Previous Session…") { commands.openPreviousSession() })
            add(simple("Add Current File as @-context") { activePanel(tabs)?.mentionCurrentFile() })
            addSeparator()
            // Git, read-only and handed straight to the IDE's own Git Log / file history — this group is the
            // only user-reachable entry point of the `git/` package. Added unconditionally: each entry HIDES
            // itself when there is no Git (see [GitContextActions]), which is re-derived on every menu open,
            // so a repository created after the tool window opened still shows up.
            addAll(GitContextActions.gearEntries(project))
            // The write half: the plugin runs no Git of its own, it asks Claude in a chat of its own — see
            // [GitPromptedActions]. Same hide-when-it-does-not-apply rule, so this adds nothing visible to a
            // project without a repository beyond the one entry that offers to create one.
            addAll(GitPromptedActions.gearEntries(project) { commands.gitChat() })
            // And the operations the IDE genuinely does better — as the IDE's OWN actions, not copies of them.
            add(GitIdeMenu.gearEntry())
            addSeparator()
            add(
                simple("Settings…") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, ClaudeSettingsConfigurable::class.java)
                },
            )
        }

    /**
     * Keeps tab labels short so many open chats don't push the tab strip off-screen; full title lives in the
     * tooltip. The ellipsis rule itself is shared with the "Open Previous Session" chooser
     * ([TabSessionCommands.truncate]) so the two lists of conversations cut in the same place.
     */
    private fun tabTitle(title: String): String =
        TabSessionCommands.truncate(title.trim().ifBlank { "Chat" }, TAB_TITLE_MAX)

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
            // Greyed while there is no chat on screen, and so no session to sign out of.
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
        /** The id this plugin registers its tool window under (`plugin.xml`), and looks it up by. */
        const val TOOL_WINDOW_ID = "Claude Code"

        /**
         * The chat panel currently on screen, for callers OUTSIDE the tool window (the editor actions).
         *
         * The one place that knows the shape of the content, deliberately: the tool window holds a SINGLE
         * `Content` whose component is the [ChatTabsPanel] (the chats are its cards), so casting
         * `selectedContent.component` to a `JcefChatPanel` — which is what `actions/` did — is a cast that can
         * never succeed. It compiled, it returned null, and the actions silently took their fallback branch.
         *
         * Null when the tool window has never been opened: its content, and therefore every chat, is built by
         * [createToolWindowContent]. Callers that need a chat must [ToolWindow.activate] first and ask again.
         */
        fun activePanel(project: Project): JcefChatPanel? {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return null
            val strip = toolWindow.contentManager.selectedContent?.component as? ChatTabsPanel ?: return null
            return strip.selectedChat
        }

        /** Min gap between attention notifications for the same session, to avoid spam. */
        const val NOTIFY_THROTTLE_MS = 3000L

        /** Max characters in a chat tab label before it's ellipsized (full title stays in the tooltip). */
        const val TAB_TITLE_MAX = 22
    }
}
