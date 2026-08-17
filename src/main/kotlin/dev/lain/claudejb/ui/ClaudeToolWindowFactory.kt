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
        val commands = TabSessionCommands(project, tabs) { session, select -> openChat(project, tabs, session, select) }
        commands.restoreOrCreate()
        // The one door onto the tab-level commands for callers outside this file: the composer's own action
        // buttons, and the Git view's buttons, which reach them from whichever chat is drawing them.
        tabs.commands = commands

        // NO title actions. New chat, Stop, Commands, Git, Close diffs and Log out were six Swing `AnAction`s
        // here — the last piece of this UI that was not the browser, in a strip that cannot share the page's
        // accent, type scale or transitions, and as far from the composer as the tool window allows. They are
        // rows of the composer now (`app-composer-actions.js`), which is where every one of them is used.
        // What is left above is the platform's own: the gear and the hide button.
        toolWindow.setAdditionalGearActions(buildGearGroup(project, tabs, commands))
    }

    /** Starts [session]'s process, then adds a tab for it and wires it, selecting it unless told not to. */
    private fun openChat(project: Project, tabs: ChatTabsPanel, session: ClaudeSession, select: Boolean = true) {
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
        //
        // Not selecting is the Git chat opening itself behind a button press the user made somewhere else; the
        // tab is there, badged when its turn wants attention, and they go to it when they choose to.
        if (select) tabs.select(tab)
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
            // Through the chat on screen, so the conversation is created the ONE way that also applies the
            // settings and starts the process — and so the turn appears in the view the user can open. The
            // fallback is the bare session for a menu somehow opened with no chat built: it still works,
            // because `ClaudeSession.send` starts the process itself and queues until it is up.
            addAll(
                GitPromptedActions.gearEntries(project) {
                    activePanel(project)?.gitChat?.session()
                        ?: ChatSessionManager.getInstance(project).gitChatOrCreate()
                },
            )
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

    // NB the six title-bar `AnAction`s that used to live here — New Chat, Log out, Interrupt, Commands, Git
    // and Close All Diffs — are gone, not disabled: they are buttons on the composer now
    // (`app-composer-actions.js`), and a class nobody registers is exactly the kind of thing this repository
    // keeps rediscovering months later.

    companion object {
        /** The id this plugin registers its tool window under (`plugin.xml`), and looks it up by. */
        const val TOOL_WINDOW_ID = "Claude Code"

        /**
         * The tab strip, for callers OUTSIDE the tool window.
         *
         * The one place that knows the shape of the content, deliberately: the tool window holds a SINGLE
         * `Content` whose component is the [ChatTabsPanel] (the chats are its cards), so casting
         * `selectedContent.component` to a `JcefChatPanel` — which is what `actions/` did — is a cast that can
         * never succeed. It compiled, it returned null, and the actions silently took their fallback branch.
         *
         * Null when the tool window has never been opened: its content, and therefore every chat, is built by
         * [createToolWindowContent]. Callers that need a chat must [ToolWindow.activate] first and ask again.
         */
        private fun tabsPanel(project: Project): ChatTabsPanel? {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return null
            return toolWindow.contentManager.selectedContent?.component as? ChatTabsPanel
        }

        /** The chat panel currently on screen — see [tabsPanel] for when this is null. */
        fun activePanel(project: Project): JcefChatPanel? = tabsPanel(project)?.selectedChat

        /**
         * This project's chat strip — the fallback behind [JcefChatPanel.chatStrip].
         *
         * A panel that is not (yet) in the Swing hierarchy cannot find its container by walking up, and every
         * message that needed it was dropped by a `?.` in the meantime. There is one strip per project and it
         * is here, so this is the answer that does not depend on when it is asked.
         */
        internal fun chatTabs(project: Project): ChatTabsPanel? = tabsPanel(project)

        /**
         * A REAL component inside the tool window, for building a platform action's data context.
         *
         * `SimpleDataContext.getProjectContext(project)` carries one key. A platform action resolves its
         * target from the context it is given and disables itself when it cannot — and a disabled action
         * invoked through `ActionUtil.performAction` does nothing, silently. Handing it the component the
         * user actually pressed from lets `DataManager` fill in everything the platform would normally have,
         * and gives a popup somewhere to anchor.
         */
        fun contextComponent(project: Project): JComponent? = tabsPanel(project)

        /** A fresh chat in a new tab — the composer's *New chat* button, from inside whichever chat it is in. */
        fun newChat(project: Project) {
            tabsPanel(project)?.commands?.newChat()
        }

        /**
         * The composer's *Git* button: the Git view, **in the chat you are already in**.
         *
         * It used to switch you to a Git TAB and open the view there. Two things were wrong with that and
         * both are gone: the view is a view of the REPOSITORY, which is the same repository from every chat,
         * so being sent to another conversation to look at it was a detour; and that conversation's
         * transcript is empty by design, so a press that landed before the view did showed a blank page.
         * The Git conversation still exists — it is embedded in the view now ([GitChatFeed]) — but reaching
         * the view no longer means going anywhere.
         *
         * [JcefChatPanel.pushGit] first, and it is not an optimisation: the view draws a branch, a change
         * list and a history collected off the repository, and a panel that has never collected them renders
         * "No Git repository for this project" until the read lands. The page tolerates the order either way
         * — `cc.showGitView` re-arms a once-only open, so the view appears when the data does — but pressing
         * a button and reading that sentence is a lie.
         *
         * Null tool window does nothing, as in [activePanel]: the button is drawn inside it, so a press
         * cannot arrive before its content exists.
         */
        fun showGitView(project: Project) {
            val panel = activePanel(project) ?: return
            panel.pushGit()
            panel.host.exec("window.cc.showGitView && window.cc.showGitView()")
        }

        /** Min gap between attention notifications for the same session, to avoid spam. */
        const val NOTIFY_THROTTLE_MS = 3000L

        /** Max characters in a chat tab label before it's ellipsized (full title stays in the tooltip). */
        const val TAB_TITLE_MAX = 22
    }
}
