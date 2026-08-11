package dev.lain.claudejb.ui

import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.ide.highlighter.ArchiveFileType
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.ui.LafManagerListener
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBPanel
import dev.lain.claudejb.context.Attachment
import dev.lain.claudejb.context.EditorContextProvider
import dev.lain.claudejb.context.FilePickerHelper
import dev.lain.claudejb.diff.DiffPresenter
import dev.lain.claudejb.protocol.mergedOver
import dev.lain.claudejb.session.AttentionReason
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.EntryDTO
import dev.lain.claudejb.session.PluginAgentIndex
import dev.lain.claudejb.session.SessionListener
import dev.lain.claudejb.session.TranscriptEntry
import dev.lain.claudejb.session.TranscriptModel
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.Provider
import dev.lain.claudejb.ui.jcef.JcefBridge
import dev.lain.claudejb.ui.jcef.JcefHost
import dev.lain.claudejb.ui.jcef.JcefSessionData
import dev.lain.claudejb.ui.jcef.JcefState
import dev.lain.claudejb.ui.jcef.JcefTabsData
import dev.lain.claudejb.ui.jcef.JcefTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import javax.swing.Timer

/**
 * The JCEF tool-window tab content: a THIN assembler that binds one [ClaudeSession] to the embedded web view.
 * It owns no rendering logic and no serialization — the browser plumbing lives in [JcefHost], the JSON shapes
 * in [JcefBridge]/[JcefState]/[JcefTheme]. This class only wires backend events to `window.cc.*` pushes and
 * routes inbound bridge messages back to the session (all on the EDT, where the listeners fire and the host
 * delivers messages).
 *
 * Streaming is coalesced: rapid transcript deltas accumulate a dirty-id set and a structural flag, drained by a
 * 30ms Swing timer into a single `cc.batch` frame per tick (the frontend upserts each row by id and repositions
 * it to its order), so the page never sees one DOM write per token.
 */
class JcefChatPanel(private val project: Project, val session: ClaudeSession) :
    JBPanel<JcefChatPanel>(BorderLayout()), Disposable, SessionListener, TranscriptModel.Listener {

    private val logger = com.intellij.openapi.diagnostic.Logger.getInstance(JcefChatPanel::class.java)

    private val host = JcefHost(this, ::onBridgeMessage)

    /** Where a clicked link goes — the browser, an editor, or the Project view. See [LinkNavigator]. */
    private val links = LinkNavigator(project)

    // ── Streaming coalescer state (all touched on the EDT) ───────────────────────────────────────────────
    private val dirty = LinkedHashSet<Long>()
    private var structural = false
    private val timer = Timer(ELAPSED_TICK_MS) { onTick() }.apply { isRepeats = true }

    /** Pending attachments pinned to the next turn (editor actions, drag/drop/paste, file picker). */
    private val tray = AttachmentTray(project, host::exec, ::focusInput)

    /**
     * Actions deferred by [whenReady]. EDT-confined: both the add and the drain happen on the EDT.
     *
     * MUST be declared BEFORE the `init` block: Kotlin runs property initializers and `init` blocks in
     * declaration order, so a list declared below `init` is still null while `init` runs — and `init` calls
     * [whenReady] three times. Declaring it after threw NPE inside the constructor, which took the whole tab
     * with it: no chat could be opened or restored at all.
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
    private val feed = SessionFeed(session, host::exec) {
        // BOTH surfaces, or they disagree: `usage` feeds the dashboard bars (pushSession) AND the composer's
        // usage dots (pushMetaState → stateJson). Pushing only the dashboard left the dots blank until some
        // unrelated state change re-pushed them — the same number appearing "a while later" in one place.
        pushSession()
        pushMetaState()
    }

    /** Last observed process liveness, so [onStateChanged] can spot a restart. EDT-confined. */
    private var wasRunning = false

    /** The two onboarding cards' host side (install-the-binary + sign-in), kept OFF this class on purpose. */
    private val onboarding = OnboardingController(project, session, host::exec)

    /**
     * The chat list this page draws in its tab bar.
     *
     * Pushed in by [ChatTabsPanel] (see [setChats]) because no single page owns the list — there is one
     * browser per chat, and each renders the whole bar marking its own entry.
     */
    private var chats: List<JcefTabsData.Chat> = emptyList()

    // Declared ABOVE `init`, which assigns them. Kotlin runs initializers and init blocks in declaration
    // order, so a field declared below would still be null when the constructor writes it — the defect that
    // killed every chat tab in 5.0.0 and the reason InitOrderContractTest scans this file.

    /** Wired in `init` to [onAgentsScanned]; a field so a test or a future owner can substitute it. */
    var onAgentsUpdated: (List<String>) -> Unit = {}

    /** Wired in `init` to [revealAgent]. */
    var onRevealAgent: (String) -> Unit = {}

    /** Agents whose tab the user closed. Not a delete — see [PluginAgentIndex]; the card reopens them. */
    private val hiddenAgents = HashSet<String>()

    /**
     * Which transcript this browser is painting: the chat's own, an agent's, or a background task's view.
     *
     * One browser, many transcripts. A JCEF per agent tab would mean a Chromium process per agent, and the
     * session this feature exists for runs dozens at once.
     *
     * Declared ABOVE `init` for the same reason as the fields above it: Kotlin runs initializers in
     * declaration order, so a property declared below is still null while the constructor runs — the defect
     * that killed every chat tab in 5.0.0, which is why InitOrderContractTest scans this file.
     */
    private var shown: Shown = Shown.Chat

    /**
     * The last payload sent by [pushEntries], so an unchanged repaint can be skipped.
     *
     * Declared here, above `init`, for the same declaration-order reason as [shown]: `init` reaches
     * [pushEntries] through [renderAgentRows], so a property declared below would be reset to null right
     * after the constructor had set it.
     */
    private var lastPushed: String? = null

    /** What the single browser is painting. One type, so "an agent AND a task" cannot be represented. */
    private sealed interface Shown {
        object Chat : Shown
        data class Agent(val id: String) : Shown
        data class Task(val id: String) : Shown
    }

    init {
        background = ChatTheme.BG
        // The page is the whole panel: the tab bar is part of it (app-tabs.js), not a Swing strip on top.
        add(host.component, BorderLayout.CENTER)
        onAgentsUpdated = ::onAgentsScanned
        onRevealAgent = ::revealAgent
        // What the user closed in an earlier run stays closed: the index is read once here, before the first
        // scan can render anything, so a restored chat never flashes a tab the user had dismissed.
        session.sessionId?.let { id ->
            val index = PluginAgentIndex.getInstance(project)
            hiddenAgents += index.admittedAgents(id) - index.openAgents(id).toSet()
        }

        // Paint whatever the session already knows, and ask for a fresh look at the directory.
        //
        // A restored chat restores its agents from disk BEFORE this panel exists, so the scan that found them
        // fired into a session with no listener attached: the tree was in memory and the rows were empty, for
        // good — nothing scans again until an agent event arrives, and on a restored chat none ever does.
        // Rendering here (and asking for one more scan) is what makes a restored chat come back with its
        // agent rows without the user having to send a prompt first.
        renderAgentRows()
        session.scanAgents()

        livePanels.add(this)
        session.transcript.addListener(this)
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
        structural = true
        ensureTimer()
    }

    // ── TranscriptModel.Listener ─────────────────────────────────────────────────────────────────────────

    /**
     * Paints [agentId]'s transcript (null → the chat's own), replacing whatever is on screen.
     *
     * While an agent is shown, the chat's live rows are still tracked in the model but not pushed: the
     * frontend upserts by row id, so letting both streams write would interleave a live chat row into an
     * agent's transcript — the very mixing this release removes. Switching back re-sends the chat in full.
     */
    fun showTranscript(agentId: String?) {
        show(agentId?.let { Shown.Agent(it) } ?: Shown.Chat)
    }

    /**
     * Paints background task [taskId]: what it is, who started it, and whatever output has come back.
     *
     * A task has no transcript — it is a process, not a conversation — so this is built from what the binary
     * reported about it. It is deliberately NOT its owner's transcript: sending the user there is what made
     * clicking a task's tab look broken.
     */
    fun showBackgroundTask(taskId: String) = show(Shown.Task(taskId))

    private fun show(next: Shown) {
        // Whatever the transcript is about to become, it lives in the chat area — so leave the dashboard if
        // it is covering it. Selecting a tab used to repaint behind an open panel, which reads as the click
        // doing nothing at all.
        host.exec("window.cc.closeDashboard && window.cc.closeDashboard()")
        if (shown == next) return
        shown = next
        dirty.clear()
        lastPushed = null // a different thing is being shown; the skip-if-unchanged guard must not hold it back
        host.exec("window.cc.clear && window.cc.clear()")
        when (next) {
            is Shown.Chat -> {
                // Nothing in the rows is current any more, and the bar has to say so — otherwise a pill stays
                // highlighted for a transcript that is no longer on screen.
                host.exec("window.cc.clearAgentSelection && window.cc.clearAgentSelection()")
                structural = true
                ensureTimer()
            }

            is Shown.Agent -> pushEntries(session.runningAgents.nodes[next.id]?.entries.orEmpty())

            is Shown.Task -> pushEntries(BackgroundTaskView.entries(session, next.id), expanded = true)
        }
    }

    /** Re-sends whatever is shown besides the chat, after a scan or a state change. */
    private fun refreshShownAgent() {
        when (val current = shown) {
            is Shown.Chat -> Unit

            is Shown.Agent -> pushEntries(session.runningAgents.nodes[current.id]?.entries.orEmpty())

            // The point of the task view: its output grows while you are looking at it.
            is Shown.Task -> pushEntries(BackgroundTaskView.entries(session, current.id), expanded = true)
        }
    }

    /**
     * Paints a reconstructed transcript (an agent's, or a background task's view).
     *
     * **Skips the repaint when nothing changed**, and that is not an optimisation: this is called on every
     * state fire so a task's output can grow while you watch it, and clearing the page to re-send identical
     * rows several times a turn is exactly the flicker the user saw.
     */
    private fun pushEntries(entries: List<EntryDTO>, expanded: Boolean = false) {
        // Agent labels and in-flight calls, so a card inside an agent's transcript reads and behaves like one
        // in the chat: `Agent (…)` / `Subagent (…)`, and still fading while its agent works.
        val titles = HashMap<String, String>()
        val running = HashSet<String>()
        session.runningAgents.nodes.values.forEach { node ->
            val tool = node.meta.toolUseId ?: return@forEach
            node.meta.description?.takeIf { it.isNotBlank() }?.let { titles[tool] = "${node.kindLabel} ($it)" }
            if (node.status == dev.lain.claudejb.session.AgentStatus.RUNNING) running += tool
        }
        // Is the thing whose transcript is on screen still working? A call with no result is only in flight
        // while something can still return it; in a stopped agent's transcript it was cut off.
        val ownerRunning = when (val current = shown) {
            is Shown.Agent -> session.runningAgents.nodes[current.id]?.status ==
                dev.lain.claudejb.session.AgentStatus.RUNNING

            is Shown.Task -> session.backgroundTaskRegistry.all.firstOrNull { it.taskId == current.id }?.running == true

            else -> false
        }
        val payload =
            if (entries.isEmpty()) {
                ""
            } else {
                JcefBridge.agentBatchJson(entries, titles, running, expanded, ownerRunning)
            }
        if (payload == lastPushed) return
        lastPushed = payload
        host.exec("window.cc.clear && window.cc.clear()")
        if (payload.isNotEmpty()) {
            host.exec("window.cc.batch && window.cc.batch($payload)")
        }
    }

    override fun onAdded(entry: TranscriptEntry, index: Int) {
        // Append-at-tail (the common streaming case) leaves every existing row's order unchanged, so we only need
        // to send the NEW row (the dirty path, same as a streaming text update) instead of re-serializing the
        // whole transcript on every added row — the previous unconditional `structural = true` was O(N²) across a
        // turn and made the transcript visibly flicker. A middle insert shifts following rows' orders, so it still
        // needs a full structural resend.
        if (index < session.transcript.entries.size - 1) structural = true
        dirty.add(entry.id)
        ensureTimer()
    }

    override fun onUpdated(entry: TranscriptEntry) {
        dirty.add(entry.id)
        ensureTimer()
    }

    override fun onCleared() {
        dirty.clear()
        structural = false
        host.exec("window.cc.clear && window.cc.clear()")
    }

    private fun ensureTimer() {
        if (!timer.isRunning) timer.start()
    }

    /** Coalescer tick (EDT): one `cc.batch` frame — all rows on a structural change, else just the dirty ones. */
    private fun onTick() {
        // An agent's transcript (or a task's view) is on screen: keep coalescing the chat's rows into the
        // model, but do not paint them over it. They are re-sent whole when the user switches back.
        if (shown != Shown.Chat) {
            dirty.clear()
            structural = true
            timer.stop()
            return
        }
        val entries = session.transcript.entries
        val items: List<Pair<TranscriptEntry, Int>> = if (structural) {
            structural = false
            entries.mapIndexed { index, entry -> entry to index }
        } else {
            val idToIndex = HashMap<Long, Int>(entries.size)
            entries.forEachIndexed { index, entry -> idToIndex[entry.id] = index }
            dirty.mapNotNull { id ->
                val idx = idToIndex[id] ?: return@mapNotNull null
                entries[idx] to idx
            }
        }
        dirty.clear()
        if (items.isNotEmpty()) {
            host.exec("window.cc.batch && window.cc.batch(" + JcefBridge.batchJson(items) + ")")
        }
        if (dirty.isEmpty() && !structural) timer.stop()
    }

    // ── SessionListener ──────────────────────────────────────────────────────────────────────────────────

    /**
     * The agent tree moved: repaint the strips, refresh the shown agent's transcript, and let whoever owns
     * the tab strips (the tool window factory) blink and notify for the newly-admitted ones.
     */
    override fun onAgentsChanged(freshlyAdmitted: List<String>) {
        onAgentsUpdated(freshlyAdmitted)
        refreshShownAgent()
        pushSession()
    }

    /**
     * Repaints the row stack from the registry, minus whatever the user has closed.
     *
     * The owner of a background task is resolved through the edge stream: `background_tasks_changed` carries
     * no parent, but the same `task_id` seen earlier as a subagent task does carry the `tool_use_id` that
     * names an agent. When that lookup fails the task stays at the chat's level rather than being guessed
     * into somebody's row.
     */
    private fun renderAgentRows() {
        // Every open chat's session, so hovering ANY tab can show that chat's tree — not just this one's.
        val others = chatStrip()?.workloads().orEmpty().associate { it.chatId to it.session }
        host.exec(
            "window.cc.tabs && window.cc.tabs(" +
                JcefTabsData.tabsJson(session, chats, hiddenAgents, others) + ")",
        )
    }

    /** The chat list changed (added, closed, renamed, selected): re-render this page's bar. */
    fun setChats(list: List<JcefTabsData.Chat>) {
        chats = list
        renderAgentRows()
    }

    /** The strip that owns the chats, found by walking up — it is this panel's container, not a dependency. */
    private fun chatStrip(): ChatTabsPanel? =
        javax.swing.SwingUtilities.getAncestorOfClass(ChatTabsPanel::class.java, this) as? ChatTabsPanel

    /**
     * A scan finished. Repaint the rows, then blink the tabs of agents seen for the first time and raise ONE
     * grouped notification for the burst — on a session spawning dozens at once, one popup per agent is a
     * storm, and the blink is what carries "this one is new" without interrupting.
     */
    private fun onAgentsScanned(freshlyAdmitted: List<String>) {
        renderAgentRows()
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
        val tw = ToolWindowManager.getInstance(project).getToolWindow(CLAUDE_TOOL_WINDOW)
        if (tw != null && tw.isVisible && isShowing) return
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
                    ToolWindowManager.getInstance(project).getToolWindow(CLAUDE_TOOL_WINDOW)?.activate(null)
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
    private fun revealElsewhere(chatId: String, reveal: (JcefChatPanel) -> Unit) {
        val strip = chatStrip()
        val target = chatId.takeIf { it.isNotBlank() }?.let { strip?.panelOf(it) }
        if (target == null || target === this) {
            reveal(this)
            return
        }
        strip?.selectById(chatId)
        reveal(target)
    }

    /** The host side of a `revealAgent`: resolve what it names, or fall back to the chat's own transcript. */
    internal fun revealFromHost(m: JcefBridge.Msg.RevealAgent) {
        resolveAgentId(m)?.let { onRevealAgent(it) } ?: showTranscript(null)
    }

    private fun revealAgent(agentId: String) {
        if (hiddenAgents.remove(agentId)) {
            session.sessionId?.let { PluginAgentIndex.getInstance(project).setTabOpen(it, agentId, true) }
            renderAgentRows()
        }
        // The bar opens the path down to it; the page owns which levels are shown (see app-tabs.js).
        host.exec("window.cc.revealAgentTab && window.cc.revealAgentTab(" + JcefBridge.jsString(agentId) + ")")
        showTranscript(agentId)
    }

    /**
     * Turns the open subtab into a chat tab of its own.
     *
     * The tab shows the SAME session — an agent is not a separate conversation, it is part of this one — but
     * it is pinned to that agent's (or that task's) transcript and stays there while you use the chat next to
     * it. Which is the whole point: a subtab is a view of this browser, so it disappears the moment you look
     * at something else, and the one agent you keep coming back to deserves better than being re-found.
     */
    private fun pinSubtab(m: JcefBridge.Msg.PinSubtab) {
        val strip = chatStrip() ?: return
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

    override fun onStateChanged() {
        pushMetaState()
        pushSession()
        // Background tasks arrive on this path (`background_tasks_changed` is a level signal that fires
        // state), not through the agent scan — so their rows would otherwise only refresh when an agent
        // happened to change.
        renderAgentRows()
        // …and so does a task's output, which is the whole point of its view: it has to grow while you are
        // looking at it, not when you next switch tabs. Only the TASK view is repainted here — an agent's
        // transcript changes when the scan says so, and re-serializing it on every state fire would be a
        // full repaint several times a turn for nothing.
        if (shown is Shown.Task) refreshShownAgent()
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
        // The not-found card is up → the onboarding watcher looks for the binary appearing (an install
        // finishing) and starts the session without further clicks.
        onboarding.onStateChanged()
    }

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

    override fun onMetadataChanged() {
        pushMetaState()
        pushSession()
    }
    override fun onPermissionsChanged() = pushPermissions()
    // onAttention / onTitleChanged are not overridden: SessionListener declares them with empty default
    // bodies, and an explicit no-op override adds nothing except a place for someone to wonder whether the
    // omission was intentional. The tab badge and relabel are handled by ClaudeToolWindowFactory, not here.

    // ── Push helpers ─────────────────────────────────────────────────────────────────────────────────────

    private fun pushTheme() {
        val reduceMotion = ClaudeSettings.getInstance(project).reduceMotion
        host.exec("window.cc.theme && window.cc.theme(" + JcefTheme.vars(reduceMotion) + ")")
    }

    private fun pushMetaState() {
        host.exec(
            "window.cc.meta && window.cc.meta(" + JcefState.metaJson(session) + ");" +
                "window.cc.state && window.cc.state(" + JcefState.stateJson(session, feed.usage) + ")",
        )
    }

    private fun pushPermissions() {
        val perms = session.pendingPermissions()
        val diffByRequest = computeDiffs(perms)
        host.exec(
            "window.cc.permissions && window.cc.permissions(" +
                JcefBridge.permissionsJson(perms, diffByRequest) + ")",
        )
    }

    /**
     * For each reviewable Edit/Write/MultiEdit permission, compute a read-only unified diff (current vs proposed)
     * so the card can show what's changing in red/green. Edits are accepted/rejected as a whole — there is no
     * per-line selection (it produced incoherent, broken code).
     */
    private fun computeDiffs(perms: List<dev.lain.claudejb.permission.PendingPermission>): Map<String, String> =
        perms.mapNotNull { p -> inlineDiffFor(p)?.let { p.requestId to it } }.toMap()

    /**
     * The inline unified diff for one pending permission, or null when there is nothing worth rendering.
     *
     * Runs on the EDT, so the file read and the diff are both capped: a multi-MB file would freeze the UI, and
     * an inline diff is meaningless at that size. An oversized file simply skips the inline preview ("View
     * diff" still works, and accept/reject is unaffected — the binary does its own read and write).
     */
    private fun inlineDiffFor(p: dev.lain.claudejb.permission.PendingPermission): String? {
        if (!p.reviewable || p.toolName !in DiffPresenter.REVIEWABLE_TOOLS) return null
        val path = DiffPresenter.filePathOf(p.input) ?: return null
        val file = java.io.File(path)
        if (file.isFile && file.length() > MAX_HUNK_FILE_BYTES) return null
        val current = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull() ?: ""
        val proposed = DiffPresenter.proposedContent(p.toolName, p.input, current) ?: return null
        return DiffPresenter.unifiedDiff(current, proposed).takeIf { it.isNotBlank() }
    }

    /**
     * Restore an edit: prefer the NATIVE rewind (ask Claude Code to restore the whole turn via
     * rewind_files), and only if that's unavailable offer the IDE-side per-file revert — behind a
     * confirmation with a "don't ask again" choice.
     */
    private fun rewindOrRevert(toolUseId: String) {
        val snap = session.editSnapshot(toolUseId)
        val turn = session.userMessageIdFor(toolUseId)
        if (turn != null && session.checkpointingEnabled) {
            session.requestRewindFiles(turn, dryRun = true) { probe ->
                if (probe != null && probe.canRewind) {
                    session.requestRewindFiles(turn, dryRun = false) { done ->
                        if (done != null && done.canRewind) {
                            session.refreshAfterRewind(done.filesChanged)
                            val n = done.filesChanged.size
                            tray.notify("Restored to this turn via Claude Code" + if (n > 0) " ($n file(s))." else ".")
                        } else {
                            offerIdeFallback(snap, done?.error ?: "rewind failed")
                        }
                    }
                } else {
                    offerIdeFallback(snap, probe?.error ?: "no checkpoint for this turn")
                }
            }
        } else {
            offerIdeFallback(snap, if (!session.checkpointingEnabled) "checkpointing disabled" else "no turn anchor for this edit")
        }
    }

    /** Confirmation (with a remembered choice) to fall back to the IDE-side per-file revert. */
    private fun offerIdeFallback(snap: dev.lain.claudejb.diff.EditSnapshot?, reason: String) {
        if (snap == null) {
            tray.notify("Nothing to restore for this edit.")
            return
        }
        val settings = ClaudeSettings.getInstance(project)
        when (settings.rewindFallback) {
            "ide" -> {
                session.revertEdit(snap)
                return
            }

            "never" -> {
                tray.notify("Native rewind unavailable ($reason).")
                return
            }
        }
        val doNotAsk = object : com.intellij.openapi.ui.DialogWrapper.DoNotAskOption.Adapter() {
            override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
                if (isSelected) settings.rewindFallback = if (exitCode == com.intellij.openapi.ui.Messages.YES) "ide" else "never"
            }
        }
        val restore = com.intellij.openapi.ui.MessageDialogBuilder
            .yesNo(
                "Rewind Unavailable",
                "Claude Code's native rewind isn't available for this edit ($reason).\nRestore this file via the IDE instead?",
            )
            .yesText("Restore via IDE")
            .noText("Cancel")
            .icon(com.intellij.openapi.ui.Messages.getQuestionIcon())
            .doNotAsk(doNotAsk)
            .ask(project)
        if (restore) session.revertEdit(snap)
    }

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

    /** Push the session-dashboard data (context categories, cost, account, subagents) to the web view. */
    private fun pushSession() {
        // Every open chat's tree, asked of the strip that owns them — Workloads is about what is running,
        // and that spans the tabs. Null strip (a panel outside one) degrades to this session alone.
        val json = JcefSessionData.sessionJson(session, feed.usage, chatStrip()?.workloads().orEmpty())
        // The host→web half of the data-flow trace: this is EXACTLY what the dashboard receives. An empty
        // panel with a full CC-TRACE control reply means the loss is between the session cache and here.
        LOG.debug("CC-TRACE pushSession ${json.take(TRACE_MAX)}")
        host.exec("window.cc.session && window.cc.session($json)")
    }

    /** Force a full transcript resend on the next tick (used on init and on a late page `Ready`). */
    private fun fullResync() {
        structural = true
        ensureTimer()
    }

    // ── Inbound dispatch (EDT) ───────────────────────────────────────────────────────────────────────────

    /**
     * Inbound dispatch, in two levels: pick the message group, then the message. The groups are declared on
     * [JcefBridge.Msg] and mirror the bridge's own parsers, so a message is parsed and handled by the same
     * concern — and the compiler still checks exhaustiveness at both levels, so adding a message type without
     * handling it does not compile.
     */
    private fun onBridgeMessage(json: String) {
        when (val m = JcefBridge.parse(json)) {
            is JcefBridge.Msg.Prompting -> onPrompting(m)
            is JcefBridge.Msg.Settings -> onSettings(m)
            is JcefBridge.Msg.RequestCard -> onRequestCard(m)
            is JcefBridge.Msg.Diffs -> onDiffs(m)
            is JcefBridge.Msg.Attachments -> onAttachments(m)
            is JcefBridge.Msg.SessionControl -> onSessionControl(m)
            is JcefBridge.Msg.Lifecycle -> onLifecycle(m)
        }
    }

    private fun onPrompting(m: JcefBridge.Msg.Prompting) = when (m) {
        is JcefBridge.Msg.Send -> dispatchSend(m.text)
        JcefBridge.Msg.Interrupt -> session.interrupt()
        JcefBridge.Msg.CycleMode -> session.cyclePermissionMode()
        is JcefBridge.Msg.RemoveQueued -> session.removeQueued(m.index)
        is JcefBridge.Msg.Copy -> CopyPasteManager.getInstance().setContents(StringSelection(m.text))
    }

    private fun onSettings(m: JcefBridge.Msg.Settings) = when (m) {
        is JcefBridge.Msg.ChangeModel -> session.changeModel(m.value)

        is JcefBridge.Msg.ChangeMode -> session.changePermissionMode(m.wire)

        is JcefBridge.Msg.ChangeEffort -> session.changeEffort(m.value)

        is JcefBridge.Msg.ChangeThinking ->
            session.changeThinkingTokens(if (m.on) ClaudeSession.THINKING_ON else null)

        is JcefBridge.Msg.ChangeVibe -> {
            ChatTheme.setVibeMode(m.on)
            broadcastTheme()
        }

        is JcefBridge.Msg.ChangeProvider -> session.changeProvider(Provider.fromId(m.id))
    }

    private fun onRequestCard(m: JcefBridge.Msg.RequestCard) = when (m) {
        // Edits are atomic: accept or reject the whole change (no per-line selection — it broke code coherence).
        is JcefBridge.Msg.ResolvePermission -> session.resolvePermission(m.id, m.allow)

        is JcefBridge.Msg.ResolveQuestion -> session.resolveQuestion(m.id, m.answers)

        is JcefBridge.Msg.ResolveElicitation -> session.resolveElicitation(m.id, m.action, m.content)

        is JcefBridge.Msg.AlwaysAllow -> onAlwaysAllow(m)
    }

    private fun onAlwaysAllow(m: JcefBridge.Msg.AlwaysAllow) {
        ClaudeSettings.getInstance(project).alwaysAllow.remember(m.tool)
        // Resolve THE card the button lives on (by requestId), not just the first pending card with that
        // tool name — with two pending Bash cards, "Always allow" on the second used to approve (and run)
        // the first, unseen command. Fall back to tool-name match only if the id didn't come through.
        val pending = session.pendingPermissions()
        val target = pending.firstOrNull { it.requestId == m.id }
            ?: pending.firstOrNull { it.toolName == m.tool }
        target?.let { session.resolvePermission(it.requestId, true) }
    }

    private fun onDiffs(m: JcefBridge.Msg.Diffs) = when (m) {
        is JcefBridge.Msg.ViewDiff -> {
            session.pendingPermissions().firstOrNull { it.requestId == m.id }
                ?.let { DiffPresenter.openDiff(project, it.toolName, it.input) }
            Unit
        }

        is JcefBridge.Msg.ViewDiffByTool -> {
            // Completed edit: open the native diff from the captured pre-write snapshot.
            session.editSnapshot(m.toolUseId)?.let {
                DiffPresenter.openDiff(project, it.toolName, it.input, it.beforeText)
            }
            Unit
        }

        is JcefBridge.Msg.RevertEdit -> rewindOrRevert(m.toolUseId)

        JcefBridge.Msg.OpenDiffHistory -> ClaudeToolWindowFactory.openDiffHistoryFor(project, session)

        is JcefBridge.Msg.Open -> links.open(m.url)

        is JcefBridge.Msg.ResolveLinks -> resolveLinksOffEdt(m)
    }

    private fun onAttachments(m: JcefBridge.Msg.Attachments) = when (m) {
        is JcefBridge.Msg.RemoveAttachment -> tray.remove(m.id)

        JcefBridge.Msg.PickFiles -> FilePickerHelper.chooseFiles(project).forEach(tray::addPath)

        JcefBridge.Msg.PickDirectory -> {
            FilePickerHelper.chooseDirectory(project)?.let(tray::addPath)
            Unit
        }

        JcefBridge.Msg.AttachSelection -> {
            tray.addSelection()
            Unit
        }

        JcefBridge.Msg.AttachCurrentFile -> tray.addCurrentFile()

        JcefBridge.Msg.RequestAttachData -> tray.pushMenuData()

        is JcefBridge.Msg.AttachPath -> tray.addPath(m.path)

        JcefBridge.Msg.PasteClipboard -> tray.pasteFromClipboard()

        is JcefBridge.Msg.PasteClipboardImage -> tray.pasteImageFromClipboard(m.notify)

        is JcefBridge.Msg.Attach -> tray.add(Attachment.Image(m.name, m.mediaType, m.base64))
    }

    private fun onSessionControl(m: JcefBridge.Msg.SessionControl) = when (m) {
        is JcefBridge.Msg.McpReconnect -> {
            session.reconnectMcp(m.name)
            feed.requestMcp()
        }

        is JcefBridge.Msg.McpToggle -> {
            session.toggleMcp(m.name, m.enabled)
            feed.requestMcp()
        }

        is JcefBridge.Msg.StopTask -> session.stopTask(m.taskId)

        // The transcript card (and the dashboard lists) asking to go to an agent's tab. Reopens it when the
        // user had closed it: closing hides a view, it never removes the agent or its transcript.
        //
        // With nothing to resolve it means the CHAT's own transcript — a background task the binary never
        // attributed to an agent still ran somewhere, and that somewhere is this chat.
        is JcefBridge.Msg.RevealAgent -> revealElsewhere(m.chatId) { it.revealFromHost(m) }

        is JcefBridge.Msg.RevealBackgroundTask -> revealElsewhere(m.chatId) { it.showBackgroundTask(m.taskId) }

        // The tab bar lives in the page, so its clicks arrive here like any other web→host message.
        is JcefBridge.Msg.SelectChat -> chatStrip()?.selectById(m.chatId)

        is JcefBridge.Msg.CloseChat -> chatStrip()?.closeById(m.chatId)

        // No id means the chat's own transcript: that is how the breadcrumb's first segment goes back, and
        // `Shown.Agent("")` would be a transcript for an agent that does not exist — an empty page.
        is JcefBridge.Msg.SelectAgent -> showTranscript(m.agentId.ifBlank { null })

        // Pinning is the strip's business: it owns the tabs. This panel only knows WHAT was pinned.
        is JcefBridge.Msg.PinSubtab -> pinSubtab(m)

        is JcefBridge.Msg.CloseAgent -> {
            hiddenAgents += m.agentId
            session.sessionId?.let { PluginAgentIndex.getInstance(project).setTabOpen(it, m.agentId, false) }
            showTranscript(null)
            renderAgentRows()
        }

        // Everything the two onboarding cards send (install / binary path / sign-in / logout) lives in
        // its own collaborator — see OnboardingController. `handle` returns false only for messages that
        // are not onboarding's, and every remaining SessionControl IS handled above, so falling through
        // here means a new message was added without a handler: surface it instead of ignoring it.
        else -> {
            val handled = onboarding.handle(m)
            if (!handled) logger.warn("unhandled session-control message: $m")
            Unit
        }
    }

    private fun onLifecycle(m: JcefBridge.Msg.Lifecycle) = when (m) {
        JcefBridge.Msg.Ready -> {
            host.markWebReady() // the web app is alive — cancel the first-open self-heal watchdog
            pushTheme()
            pushMetaState()
            pushPermissions()
            tray.push()
            pushSession()
            feed.requestMcp()
            feed.requestVersion()
            fullResync()
        }

        JcefBridge.Msg.OpenPalette -> {}

        // client-side overlay; nothing to do backend-side
        // INFO, not WARN. It fires once per chat tab opened, so WARN would put a warning in idea.log for a
        // healthy session — and a log that cries wolf is one nobody reads when it finally matters. INFO is the
        // IDE's default level, so it is still there when someone needs to read it back.
        is JcefBridge.Msg.Diagnostics -> logger.info("JCEF diagnostics: ${m.report}")

        is JcefBridge.Msg.Unknown -> {} // total dispatch, ignore
    }

    private fun dispatchSend(raw: String) {
        session.clearSuggestion()
        val atts = tray.take()
        val text = raw.trim()
        when {
            atts.isEmpty() && text == "/login" -> session.startLogin()

            atts.isEmpty() && BTW.matches(text.substringBefore('\n')) -> {
                val rest = text.removePrefix("/btw").trim()
                session.sendSideQuestion(rest)
            }

            else -> session.send(raw, atts)
        }
    }

    /**
     * Answers the transcript's `resolveLinks` request on a POOLED thread: symbol resolution walks the Go-to-Symbol
     * index (PSI, inside a read action) and file resolution hits the disk — neither belongs on the EDT, where a
     * cold index would freeze the IDE mid-conversation. The reply is pushed back on the EDT.
     *
     * Unresolved candidates are simply absent from the reply, so the frontend leaves them as plain text: a path
     * that doesn't exist, or a word that isn't a symbol, never becomes a dead link.
     */
    private fun resolveLinksOffEdt(m: JcefBridge.Msg.ResolveLinks) {
        if (m.paths.isEmpty() && m.symbols.isEmpty()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val resolved = runCatching {
                LinkResolver.resolvePaths(project, m.paths) + LinkResolver.resolveSymbols(project, m.symbols)
            }.getOrDefault(emptyList())
            if (resolved.isEmpty()) return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                host.exec("window.cc.links && window.cc.links(" + JcefBridge.linksJson(m.rowId, resolved) + ")")
            }, ModalityState.any())
        }
    }

    // ── Tool-window actions ──────────────────────────────────────────────────────────────────────────────

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
        session.transcript.removeListener(this)
        session.removeListener(this)
        session.detachLoginUi(onboarding)
        onboarding.dispose()
        timer.stop()
        feed.stop()
        // host disposes via the parentDisposable (this panel) registered in JcefHost.
    }

    private companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(JcefChatPanel::class.java)

        /** Trace truncation for CC-TRACE lines; matches SessionControlClient's. */
        private const val TRACE_MAX = 2000

        private val BTW = Regex("^/btw\\b.*")

        // Files larger than this skip the EDT-side hunk read/diff for hunk-by-hunk review (full accept still works).
        private const val MAX_HUNK_FILE_BYTES = 1_000_000L

        /** Tick driving the tool cards' live elapsed counters. ~33 fps: smooth, and the work per tick is trivial. */
        private const val ELAPSED_TICK_MS = 30

        /** The tool window this plugin registers; used to tell "am I on screen?" from a notification. */
        private const val CLAUDE_TOOL_WINDOW = "Claude Code"

        // Vibe Mode is global (ChatTheme.vibeMode), so a toggle on one tab must re-theme them all.
        private val livePanels = java.util.concurrent.CopyOnWriteArrayList<JcefChatPanel>()
        fun broadcastTheme() {
            livePanels.forEach { it.pushTheme() }
        }
    }
}
