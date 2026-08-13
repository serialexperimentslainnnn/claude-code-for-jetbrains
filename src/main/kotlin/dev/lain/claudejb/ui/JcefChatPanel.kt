package dev.lain.claudejb.ui

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBPanel
import dev.lain.claudejb.context.Attachment
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.SessionListener
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.ui.jcef.JcefCardPayload
import dev.lain.claudejb.ui.jcef.JcefHost
import dev.lain.claudejb.ui.jcef.JcefSessionData
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

    /** Pending attachments pinned to the next turn (editor actions, drag/drop/paste, file picker). */
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

    /** The two onboarding cards' host side (install-the-binary + sign-in), kept OFF this class on purpose. */
    internal val onboarding = OnboardingController(project, session, host::exec)

    /** The tab bar this page draws — the chats, the agents under them, and what a click on one does. */
    internal val agentTabs = ChatAgentTabs(this)

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

        // Seed the page. The host queues these until load-end, and `Ready` re-pushes everything for a late load.
        pushTheme()
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
        if (!session.turnActive && running) feed.requestPlan()
        // The not-found card is up → the onboarding watcher looks for the binary appearing (an install
        // finishing) and starts the session without further clicks.
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

    internal fun pushMetaState() {
        host.exec(
            "window.cc.meta && window.cc.meta(" + JcefState.metaJson(session) + ");" +
                "window.cc.state && window.cc.state(" + JcefState.stateJson(session, feed.usage) + ")",
        )
    }

    internal fun pushPermissions() {
        val perms = session.pendingPermissions()
        val diffByRequest = edits.diffsFor(perms)
        host.exec(
            "window.cc.permissions && window.cc.permissions(" +
                JcefCardPayload.permissionsJson(perms, diffByRequest) + ")",
        )
    }

    /** Push the session-dashboard data (context categories, cost, account, subagents) to the web view. */
    internal fun pushSession() {
        // Every open chat's tree, asked of the strip that owns them — Workloads is about what is running,
        // and that spans the tabs. Null strip (a panel outside one) degrades to this session alone.
        val json = JcefSessionData.sessionJson(session, feed.usage, chatStrip()?.workloads().orEmpty(), feed.plan)
        // The host→web half of the data-flow trace: this is EXACTLY what the dashboard receives. An empty
        // panel with a full CC-TRACE control reply means the loss is between the session cache and here.
        LOG.debug("CC-TRACE pushSession ${json.take(TRACE_MAX)}")
        host.exec("window.cc.session && window.cc.session($json)")
    }

    /** The strip that owns the chats, found by walking up — it is this panel's container, not a dependency. */
    internal fun chatStrip(): ChatTabsPanel? =
        javax.swing.SwingUtilities.getAncestorOfClass(ChatTabsPanel::class.java, this) as? ChatTabsPanel

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
    }
}
