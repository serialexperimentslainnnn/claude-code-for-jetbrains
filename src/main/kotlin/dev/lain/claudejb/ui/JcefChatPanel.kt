package dev.lain.claudejb.ui

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBPanel
import dev.lain.claudejb.context.Attachment
import dev.lain.claudejb.git.GitHistoryService
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.SessionListener
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.ui.jcef.JcefCardPayload
import dev.lain.claudejb.ui.jcef.JcefHost
import dev.lain.claudejb.ui.jcef.JcefSessionData
import dev.lain.claudejb.ui.jcef.JcefSettingsMenu
import dev.lain.claudejb.ui.jcef.JcefState
import dev.lain.claudejb.ui.jcef.JcefTheme
import java.awt.BorderLayout

/**
 * The JCEF tool-window tab content: a THIN assembler that binds one [ClaudeSession] to the embedded web view.
 * It owns no rendering logic and no serialization — the browser plumbing lives in [JcefHost], the JSON shapes
 * in [JcefState]/[JcefTheme]/[JcefCardPayload]. This class only wires backend events to `window.cc.*` pushes
 * and routes inbound bridge messages back to the session (all on the EDT, where the listeners fire and the
 * host delivers messages).
 *
 * Each subject it would otherwise grow into lives in a collaborator of its own: what the browser paints and
 * the streaming coalescer behind it ([ChatTranscriptView]), the tab bar and the agents on it
 * ([ChatAgentTabs]), the inbound dispatch table ([ChatBridgeRouter]), the diff and the restore of an edit
 * ([ChatEditReview]), plus [LinkNavigator], [SessionFeed], [AttachmentTray] and [OnboardingController]. New
 * behaviour goes into one of those, or into a new one — never back into this class.
 */
class JcefChatPanel(internal val project: Project, val session: ClaudeSession) :
    JBPanel<JcefChatPanel>(BorderLayout()), Disposable, SessionListener {

    // Everything below is declared ABOVE `init`, which uses it. Kotlin runs property initializers and init
    // blocks in declaration order, so a field declared below would still be null while the constructor runs —
    // the defect that killed every chat tab in 5.0.0 and the reason InitOrderContractTest scans this file.
    // Order within the list is a real dependency too: each collaborator binds the ones declared above it.

    /** The inbound half: every web→host message routed to whoever owns it. Bound into [host] on construction. */
    internal val router = ChatBridgeRouter(this)

    internal val host = JcefHost(this, router::dispatch)

    /** Where a clicked link goes — the browser, an editor, or the Project view. See [LinkNavigator]. */
    internal val links = LinkNavigator(project)

    /** What this browser is painting (chat / agent / background task), and the streaming coalescer behind it. */
    internal val transcript = ChatTranscriptView(session, host::exec)

    /** Pending attachments pinned to the next turn (editor actions, drag/drop/paste, the 📎 project browser). */
    internal val tray = AttachmentTray(project, host::exec, ::focusInput)

    /** The read-only diff a permission card shows, and the restore behind a completed edit. */
    internal val edits = ChatEditReview(project, session, tray::notify)

    /**
     * Actions deferred by [whenReady]. EDT-confined: both the add and the drain happen on the EDT.
     *
     * MUST be declared BEFORE the `init` block: Kotlin runs property initializers and `init` blocks in
     * declaration order, so a list declared below `init` is still null while `init` runs — and `init` calls
     * [whenReady]. Declaring it after threw NPE inside the constructor, which took the whole tab with it: no
     * chat could be opened or restored at all.
     */
    private val pendingUntilReady = mutableListOf<() -> Unit>()

    /**
     * The per-process data the dashboard draws (plan limits, MCP, version) and the poll behind it.
     *
     * Declared above `init` for the same reason as [pendingUntilReady]: `init` reads `feed.usage` (via
     * [pushMetaState]/[pushSession]) and can ask for a refresh when the session is already running. A
     * property initializer below `init` runs AFTER it, so the field would be null inside the constructor —
     * the defect `InitOrderContractTest` exists to catch.
     */
    internal val feed = SessionFeed(session, host::exec) {
        // BOTH surfaces, or they disagree: `usage` feeds the dashboard bars (pushSession) AND the composer's
        // usage dots (pushMetaState → stateJson). Pushing only the dashboard left the dots blank until some
        // unrelated state change re-pushed them — the same number appearing "a while later" in one place.
        pushSession()
        pushMetaState()
    }

    /** Last observed process liveness, so [onStateChanged] can spot a restart. EDT-confined. */
    private var wasRunning = false

    /**
     * The last payload each push helper actually sent, so a state fire that changed nothing does not cross the
     * bridge again. Mirrors the page's own `app-tabs-guard.js`/`renderIfShown()` skip on this side: `pushSession`/
     * `pushSettingsMenu`/`pushMetaState` fire on every state change — several times a turn — and are mostly
     * identical between two adjacent fires (a quota tick that moved neither cost nor context, a state change that
     * did not touch the settings menu's own fields). EDT-confined like the pushes themselves, so no synchronisation
     * is needed. Perf-only; revisit once phase 5's timings exist — if it bought nothing, revert it.
     */
    private var lastSessionJson: String? = null
    private var lastSettingsMenuJson: String? = null
    private var lastMetaState: Pair<String, String>? = null

    /** The two onboarding cards' host side (install-the-binary + sign-in), kept OFF this class on purpose. */
    internal val onboarding = OnboardingController(project, session, host::exec)

    /** The tab bar this page draws — the chats, the agents under them, and what a click on one does. */
    internal val agentTabs = ChatAgentTabs(this)

    /**
     * This page's window onto the project's ONE Git conversation — a SECOND session drawn into this page.
     *
     * It has no tab of its own, deliberately: as one it sat in the row with the user's own conversations and
     * its startup painted the full-window boot screen over whatever chat they were in. It is created on first
     * *use* and started silently; a project where nobody ever acts on the Git view never pays for it.
     *
     * **The conversation is not this panel's** — it lives in [GitChatConversation], one per project, and this
     * field is only where it gets painted. Registering here is also what paints it, so a chat opened after the
     * talking was done comes up showing the whole thing rather than an empty pane. That is exactly what it did
     * before: each panel owned its own session field and subscribed only once its own user had acted, so the
     * same conversation looked like a different (empty) one in every other tab.
     */
    internal val gitChat = GitChatFeed(this, host::exec)

    init {
        background = ChatTheme.BG
        // The page is the whole panel: the tab bar is part of it (app-tabs.js), not a Swing strip on top.
        add(host.component, BorderLayout.CENTER)

        // Paint whatever the session already knows, and ask for a fresh look at the directory.
        //
        // A restored chat restores its agents from disk BEFORE this panel exists, so the scan that found them
        // fired into a session with no listener attached: the tree was in memory and the rows were empty, for
        // good — nothing scans again until an agent event arrives, and on a restored chat none ever does.
        // Rendering here (and asking for one more scan) is what makes a restored chat come back with its
        // agent rows without the user having to send a prompt first.
        agentTabs.render()
        session.scanAgents()

        livePanels.add(this)
        session.transcript.addListener(transcript)
        session.addListener(this)
        session.attachLoginUi(onboarding) // the sign-in card renders in this panel's web view

        // Re-push the theme whenever the IDE's Look-and-Feel changes; tied to this panel's lifetime.
        val lafConn = ApplicationManager.getApplication().messageBus.connect(this)
        lafConn.subscribe(LafManagerListener.TOPIC, LafManagerListener { pushTheme() })
        Disposer.register(this, lafConn)

        // …and re-read the repository whenever the IDE's own Git plugin says one moved. Everything else that
        // refreshes the Git view is a moment we happen to ask — the page loading, a turn ending, a button on
        // the view — and switching branch is none of them, so the view went on naming the branch you had left
        // until the next turn. git4idea publishes this from a background thread, hence the hop; overlapping
        // requests collapse inside [GitIntegration], so several open chats reacting at once cost one read.
        project.service<GitHistoryService>().onRepositoryChanged(this) {
            ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) pushGit() }, ModalityState.any())
        }

        // Seed the page. The host queues these until load-end, and `Ready` re-pushes everything for a late load.
        pushTheme()
        pushSettingsMenu()
        pushMetaState()
        pushPermissions()
        tray.push()
        pushSession()
        // These three all need a live `claude` process, and the panel is constructed BEFORE session.start()
        // runs — so calling them directly here always lost. See [whenReady].
        whenReady(feed::onSessionReady)
        feed.start()
        transcript.fullResync()
    }

    // ── SessionListener ──────────────────────────────────────────────────────────────────────────────────

    /**
     * The agent tree moved: repaint the strips, refresh the shown agent's transcript, and let whoever owns
     * the tab strips (the tool window factory) blink and notify for the newly-admitted ones.
     */
    override fun onAgentsChanged(freshlyAdmitted: List<String>) {
        agentTabs.onAgentsScanned(freshlyAdmitted)
        transcript.refreshShown()
        pushSession()
    }

    override fun onStateChanged() {
        pushMetaState()
        pushSession()
        // The ⚙ menu draws the session's model, effort and permission mode, so it moves with them — and the
        // pill is the control that moves them most. The page stashes a push that lands while the menu is shut
        // and draws it on the next open, so this costs one `exec` and no layout.
        pushSettingsMenu()
        // Background tasks arrive on this path (`background_tasks_changed` is a level signal that fires
        // state), not through the agent scan — so their rows would otherwise only refresh when an agent
        // happened to change.
        agentTabs.render()
        // …and so does a task's output, which is the whole point of its view: it has to grow while you are
        // looking at it, not when you next switch tabs. Only the TASK view is repainted here — an agent's
        // transcript changes when the scan says so, and re-serializing it on every state fire would be a
        // full repaint several times a turn for nothing.
        if (transcript.showsTask) transcript.refreshShown()
        drainPendingUntilReady()
        // A RESTART is a new process, so everything that is only asked once per process has to be asked
        // again. [whenReady] fires once in the constructor and never again, so after a sign-out/sign-in the
        // dashboard sat empty until a prompt happened to produce a rate_limit_event — the panels looked
        // broken when they had simply never been asked.
        val running = session.isRunning()
        if (running && !wasRunning) feed.onSessionReady()
        wasRunning = running
        // A window moved (a rate_limit_event landed) → re-ask for all of them. The feed throttles itself.
        if (session.rateLimits.isNotEmpty()) feed.requestUsage()
        // A plan is written BY a turn, so a turn ending is the only moment one can have appeared or changed.
        // Read-only on the binary's side, and the feed redraws only when the answer actually differs.
        // …and the Git view is read back on the same edge, for the same reason: a turn is when the working
        // tree moves, so the branch, the change list and the log can only have changed here. Overlapping
        // requests collapse inside [GitIntegration], so this costs one `git log` per turn edge.
        if (!session.turnActive && running) {
            feed.requestPlan()
            pushGit()
        }
        // Which screen the tab owes the user is decided by the state this method just pushed (`binaryMissing`,
        // `needsLogin`), and the onboarding watcher re-derives that state on its own clock. All this edge is
        // for is announcing an install WE launched, once the binary turns up.
        onboarding.onStateChanged()
    }

    override fun onMetadataChanged() {
        pushMetaState()
        pushSession()
    }
    override fun onPermissionsChanged() = pushPermissions()
    // onAttention / onTitleChanged are not overridden: SessionListener declares them with empty default
    // bodies, and an explicit no-op override adds nothing except a place for someone to wonder whether the
    // omission was intentional. The tab badge and relabel are handled by ClaudeToolWindowFactory, not here.

    /**
     * Runs [action] as soon as the `claude` process is up — now if it already is.
     *
     * Every control request needs a live process, and this panel is constructed BEFORE `session.start()` is
     * called (the tool window builds the tab, then starts the session). A request issued in `init` therefore
     * finds `isRunning() == false` and is silently dropped. That is how the MCP card, the binary version and
     * the usage panel could all sit empty on a fresh tab with nothing in any log to say why — each looked like
     * its own separate bug.
     *
     * The wait is event-driven, not a poll: `onStateChanged` fires when the session flips to ready.
     */
    private fun whenReady(action: () -> Unit) {
        if (session.isRunning()) {
            action()
            return
        }
        pendingUntilReady += action
    }

    private fun drainPendingUntilReady() {
        if (pendingUntilReady.isEmpty() || !session.isRunning()) return
        val queued = pendingUntilReady.toList()
        pendingUntilReady.clear()
        queued.forEach { it() }
    }

    // ── Push helpers ─────────────────────────────────────────────────────────────────────────────────────

    internal fun pushTheme() {
        val reduceMotion = ClaudeSettings.getInstance(project).reduceMotion
        host.exec("window.cc.theme && window.cc.theme(" + JcefTheme.vars(reduceMotion) + ")")
    }

    /**
     * The composer's ⚙ menu: the settings worth changing without leaving the chat, and their current state.
     *
     * Takes the session because three of its groups — model, effort and permission mode — show what the LIVE
     * session has selected rather than what is stored, so that they and the composer's own pills cannot
     * disagree. That is also why this is pushed on every state change and not only when the menu itself was
     * used: changing the model from the pill moves the value this menu draws.
     */
    internal fun pushSettingsMenu() {
        val items = JcefSettingsMenu.json(ClaudeSettings.getInstance(project).state, session).toString()
        if (items == lastSettingsMenuJson) return
        lastSettingsMenuJson = items
        host.exec("window.cc.settingsMenu && window.cc.settingsMenu({\"items\":$items})")
    }

    /** The composer's own state: the meta document, then the live one. */
    internal fun pushMetaState() {
        val meta = JcefState.metaJson(session)
        val state = JcefState.stateJson(session, feed.usage)
        val current = meta to state
        if (current == lastMetaState) return
        lastMetaState = current
        host.exec("window.cc.meta && window.cc.meta($meta);" + "window.cc.state && window.cc.state($state)")
    }

    /**
     * The request cards on screen — this chat's, plus the Git conversation's while it has any.
     *
     * ONE region for both, because the page has one and a second would be a second place to look for the
     * thing that is blocking you. Which session answers a card rides on the card (`scope`), not on where it
     * was drawn. This chat's come first: they are the ones the user is here for.
     */
    internal fun pushPermissions() {
        val perms = session.pendingPermissions()
        // This chat's first: they are the ones the user is here for. The Git conversation's follow, tagged,
        // so answering one reaches the session that asked.
        val groups = listOf(JcefCardPayload.Group(perms, diffByRequest = edits.diffsFor(perms))) +
            gitChat.permissionGroup()
        host.exec("window.cc.permissions && window.cc.permissions(" + JcefCardPayload.permissionsJson(groups) + ")")
    }

    /**
     * Re-reads the repository and re-pushes the dashboard once the answer is in.
     *
     * The collection is what forces the two steps: `GitHistoryService.recentCommits` spawns `git log` and
     * REFUSES to run on the EDT — it logs and hands back an empty list rather than freezing the IDE — so the
     * read happens on a pooled thread and [pushSession] runs back on the EDT with the result.
     * [GitIntegration.refresh] owns both hops and collapses overlapping requests, which is what makes it safe
     * to call this on every turn edge. Its own same-payload skip is [pushSession]'s: this function builds no
     * JSON of its own, so there is nothing here to compare — a refresh that changed nothing still reaches
     * [pushSession], which is where the repeat is caught.
     */
    internal fun pushGit() = GitIntegration.getInstance(project).refresh(::pushSession)

    /** Push the session-dashboard data (context categories, cost, account, Git, subagents) to the web view. */
    internal fun pushSession() {
        // Every open chat's tree, asked of the strip that owns them — Workloads is about what is running,
        // and that spans the tabs. Null strip (a panel outside one) degrades to this session alone.
        val json = JcefSessionData.sessionJson(
            session,
            // The retention window and the instant to measure it from, read ONCE for the whole push: every
            // chat the diagram draws is then aged by the same instant rather than by its own.
            windowMinutes = ClaudeSettings.getInstance(project).workloadWindowMinutes,
            nowMillis = System.currentTimeMillis(),
            usage = feed.usage,
            workloads = chatStrip()?.workloads().orEmpty(),
            plan = feed.plan,
            // Whatever was last collected — null until the first [pushGit], which omits the view rather than
            // drawing an empty repository over one that simply has not been read yet.
            git = GitIntegration.getInstance(project).snapshot(),
        )
        if (json == lastSessionJson) return
        lastSessionJson = json
        // The host→web half of the data-flow trace: this is EXACTLY what the dashboard receives. An empty
        // panel with a full CC-TRACE control reply means the loss is between the session cache and here.
        LOG.debug("CC-TRACE pushSession ${json.take(TRACE_MAX)}")
        host.exec("window.cc.session && window.cc.session($json)")
    }

    /**
     * The strip that owns the chats.
     *
     * Two ways of asking, and the second one is why the close button did nothing. Walking up the Swing
     * hierarchy is the honest answer while this panel is IN it — it is a container, not a dependency — but a
     * panel is constructed and wired before it is added to anything, and a PINNED view is a second panel over
     * the same session that may be built the same way. Every render, every close and every selection that
     * arrived in that window resolved to null and was dropped in silence: `panel.chatStrip()?.closeById(…)`
     * is a no-op with a `?.` in front of it, which is the shape this repository has been bitten by before.
     *
     * So the tool window is asked when the walk comes up empty. There is exactly one strip per project and
     * the factory holds it, so that answer is right whenever the walk's is — and right in the window where
     * the walk has nothing to say.
     */
    internal fun chatStrip(): ChatTabsPanel? =
        (javax.swing.SwingUtilities.getAncestorOfClass(ChatTabsPanel::class.java, this) as? ChatTabsPanel)
            ?: ClaudeToolWindowFactory.chatTabs(project)

    // ── Tool-window actions ──────────────────────────────────────────────────────────────────────────────

    /** Refresh the session data and open the JCEF dashboard (the ⚙ menu reuses this instead of text dialogs). */
    fun openDashboard() {
        pushSession()
        feed.requestMcp()
        feed.requestVersion()
        // Opening the dashboard is one of the two documented refresh triggers for the plan limits (the other
        // is a rate_limit_event). Throttled inside the feed, so re-opening is free.
        feed.requestUsage()
        host.exec("window.cc.openDashboard && window.cc.openDashboard()")
    }

    /**
     * The component the tool-window `Content` hands keyboard focus to — resolved **lazily**, since CEF's real input
     * component only exists once the native browser has been created, well after the tab is built. Without it the
     * platform has nowhere to put the focus when the tab is selected (a `JBPanel` is not focusable).
     * See [ClaudeToolWindowFactory.openChat].
     */
    fun focusTarget(): javax.swing.JComponent? = host.inputComponent()

    /** Focus the chat: the browser takes the keyboard focus, and the caret lands in the composer. */
    fun focusInput() = host.requestFocus()

    fun showCommandPalette() = host.exec("window.cc.openPalette && window.cc.openPalette()")

    /**
     * Signs out — the same route the dashboard's account row takes, so there is exactly ONE logout sequence.
     * It is delicate (stop the process first, then clear, then start into a session with no identity) and
     * lives commented in [OnboardingController.logout]; this is a delegate, never a second copy of it.
     */
    fun requestLogout() = onboarding.logout()

    /** Pins the current editor file as a removable attachment chip (editor "Add … to Claude Context"). */
    fun mentionCurrentFile() = tray.addCurrentFile()

    /** Pins an attachment (file / selection / image) to the next turn as a chip; it travels with the next send. */
    fun addAttachment(attachment: Attachment) = tray.add(attachment)

    override fun dispose() {
        livePanels.remove(this)
        session.transcript.removeListener(transcript)
        session.removeListener(this)
        session.detachLoginUi(onboarding)
        onboarding.dispose()
        transcript.stop()
        feed.stop()
        // Unregisters this page from the Git conversation, and nothing else: that session belongs to the
        // project, not to this panel — every chat's Git view is a window onto the same one, so ending it here
        // would kill it for the tabs still open on it, and the pending card with it.
        gitChat.dispose()
        // host disposes via the parentDisposable (this panel) registered in JcefHost.
    }

    internal companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(JcefChatPanel::class.java)

        /** Trace truncation for CC-TRACE lines; matches SessionControlClient's. */
        private const val TRACE_MAX = 2000

        // Vibe Mode is global (ChatTheme.vibeMode), so a toggle on one tab must re-theme them all.
        private val livePanels = java.util.concurrent.CopyOnWriteArrayList<JcefChatPanel>()
        fun broadcastTheme() {
            livePanels.forEach { it.pushTheme() }
        }

        /**
         * Re-push the dashboard to every open chat.
         *
         * For the same reason as [broadcastTheme]: the retention window is a GLOBAL setting and the Workloads
         * diagram spans every chat, so changing it from one tab and redrawing only that tab leaves the others
         * showing a window nobody has any more.
         */
        fun pushSessionToAll() {
            livePanels.forEach { it.pushSession() }
        }

        /**
         * Re-push the composer's ⚙ menu to every open chat.
         *
         * Same rule as the two above, and the sharpest case of it: these switches are GLOBAL, five of them
         * are the deterministic guard's rules, and a menu still showing "Block credential files" ticked in
         * another tab after it was unticked here is a security control misreporting its own state.
         */
        fun pushSettingsMenuToAll() {
            livePanels.forEach { it.pushSettingsMenu() }
        }
    }
}
