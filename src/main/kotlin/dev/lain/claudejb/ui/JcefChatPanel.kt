package dev.lain.claudejb.ui

import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.ide.highlighter.ArchiveFileType
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.ui.LafManagerListener
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
import dev.lain.claudejb.session.SessionListener
import dev.lain.claudejb.session.TranscriptEntry
import dev.lain.claudejb.session.TranscriptModel
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.Provider
import dev.lain.claudejb.ui.jcef.JcefBridge
import dev.lain.claudejb.ui.jcef.JcefHost
import dev.lain.claudejb.ui.jcef.JcefSessionData
import dev.lain.claudejb.ui.jcef.JcefState
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

    // ── Streaming coalescer state (all touched on the EDT) ───────────────────────────────────────────────
    private val dirty = LinkedHashSet<Long>()
    private var structural = false
    private val timer = Timer(ELAPSED_TICK_MS) { onTick() }.apply { isRepeats = true }

    // ── Pending attachments pinned to the next turn (editor actions, drag/drop/paste, file picker) ────────
    private val attachments = LinkedHashMap<String, Attachment>()
    private var nextAttachmentId = 0L

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
     * The last `get_usage` reply, and when it was asked for. Cached because [pushSession] runs on every state
     * change (many per turn) while the usage figures move on the order of minutes — re-asking the binary each
     * time would be a round-trip per keystroke-ish event for a number that has not changed.
     *
     * Declared above `init` for the same reason as [pendingUntilReady]: `init` reads `lastUsage` (via
     * [pushMetaState]/[pushSession]) and can write `lastUsageAt` (via [requestUsage], when the session is
     * already running). A property initializer below `init` runs AFTER it and would silently reset the throttle
     * it had just set. Nullable and primitive types hide this — they read as null/0 rather than throwing — which
     * is precisely why it is worth stating instead of relying on someone noticing.
     */
    private var lastUsage: dev.lain.claudejb.protocol.UsageReport? = null
    private var lastUsageAt = 0L

    /**
     * Plan-limits poll, unconditional for the panel's whole lifetime.
     *
     * Unlike context and cost — which cannot move while the session idles, so their timer retires at turn end
     * — the quota IS shared state: other sessions, other devices and claude.ai itself consume the same
     * windows, and **a window reset is a wall-clock event that owes nothing to this IDE**.
     *
     * It used to be gated on [isShowing], and that gate is the bug: a tool window the user had collapsed, or
     * a chat tab that was not the selected one, stopped asking entirely — so a window could reset, or fill
     * from another device, and the panel went on displaying the last figure it happened to catch until
     * something else (a turn, opening the dashboard) triggered a probe. "It only updates when I talk to the
     * agent" is exactly what a visibility-gated poll looks like from outside. The round-trip it saved is one
     * control request every half minute against a process that is already running.
     */
    private val usageTimer = Timer(USAGE_POLL_MS) { requestUsage() }.apply { isRepeats = true }

    /** Last observed process liveness, so [onStateChanged] can spot a restart. EDT-confined. */
    private var wasRunning = false

    /** Everything that is per-PROCESS rather than per-panel: asked on every launch, not just the first. */
    private fun onSessionReady() {
        requestMcp()
        requestVersion()
        requestUsage()
    }

    /** The two onboarding cards' host side (install-the-binary + sign-in), kept OFF this class on purpose. */
    private val onboarding = OnboardingController(project, session, host::exec)

    init {
        background = ChatTheme.BG
        add(host.component, BorderLayout.CENTER)

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
        pushAttachments()
        pushSession()
        // These three all need a live `claude` process, and the panel is constructed BEFORE session.start()
        // runs — so calling them directly here always lost. See [whenReady].
        whenReady(::onSessionReady)
        usageTimer.start()
        structural = true
        ensureTimer()
    }

    // ── TranscriptModel.Listener ─────────────────────────────────────────────────────────────────────────

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

    override fun onStateChanged() {
        pushMetaState()
        pushSession()
        drainPendingUntilReady()
        // A RESTART is a new process, so everything that is only asked once per process has to be asked
        // again. [whenReady] fires once in the constructor and never again, so after a sign-out/sign-in the
        // dashboard sat empty until a prompt happened to produce a rate_limit_event — the panels looked
        // broken when they had simply never been asked.
        val running = session.isRunning()
        if (running && !wasRunning) onSessionReady()
        wasRunning = running
        // A window moved (a rate_limit_event landed) → re-ask for all of them. requestUsage throttles itself.
        if (session.rateLimits.isNotEmpty()) requestUsage()
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
                "window.cc.state && window.cc.state(" + JcefState.stateJson(session, lastUsage) + ")",
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
                            notifyClipboard("Restored to this turn via Claude Code" + if (n > 0) " ($n file(s))." else ".")
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
            notifyClipboard("Nothing to restore for this edit.")
            return
        }
        val settings = ClaudeSettings.getInstance(project)
        when (settings.rewindFallback) {
            "ide" -> {
                session.revertEdit(snap)
                return
            }

            "never" -> {
                notifyClipboard("Native rewind unavailable ($reason).")
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

    /** A small balloon for clipboard feedback (e.g. when "Paste image" finds nothing to paste). */
    private fun notifyClipboard(message: String) {
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("Claude Code")
            .createNotification(message, com.intellij.notification.NotificationType.INFORMATION)
            .notify(project)
    }

    /** Refresh the session data and open the JCEF dashboard (the ⚙ menu reuses this instead of text dialogs). */
    fun openDashboard() {
        pushSession()
        requestMcp()
        requestVersion()
        // Opening the dashboard is one of the two documented refresh triggers for the plan limits (the other is
        // a rate_limit_event). It was stated in requestUsage's contract and not actually wired, so the bars
        // showed whatever the last unrelated refresh had left. Throttled, so re-opening is free.
        requestUsage()
        host.exec("window.cc.openDashboard && window.cc.openDashboard()")
    }

    /** Push the session-dashboard data (context categories, cost, account, subagents) to the web view. */
    private fun pushSession() {
        val json = JcefSessionData.sessionJson(session, lastUsage)
        // The host→web half of the data-flow trace: this is EXACTLY what the dashboard receives. An empty
        // panel with a full CC-TRACE control reply means the loss is between the session cache and here.
        LOG.debug("CC-TRACE pushSession ${json.take(TRACE_MAX)}")
        host.exec("window.cc.session && window.cc.session($json)")
    }

    /**
     * Refreshes the plan-limit windows, then re-pushes the dashboard.
     *
     * Called by [usageTimer] every [USAGE_POLL_MS] while the panel is showing, and directly on the event
     * triggers (a `rate_limit_event`, the dashboard opening, session ready). The throttle below is burst
     * protection for the event triggers — a run of rate_limit_events must not turn into a request storm —
     * and its floor sits under the timer's period so the periodic tick is never swallowed by it.
     */
    private fun requestUsage() {
        val now = System.currentTimeMillis()
        // The throttle must not swallow the FIRST reading: with no data yet there is nothing to protect, and
        // waiting out the interval is the difference between the panel appearing at once and appearing later
        // for no reason the user can see.
        if (lastUsage != null && now - lastUsageAt < USAGE_MIN_INTERVAL_MS) return
        lastUsageAt = now
        session.requestUsage { report ->
            if (report != null) {
                // Merged, not replaced: when the binary's usage fetch falls back to its header-seeded object
                // the reply carries only five_hour/seven_day, and taking it literally made the per-model bars
                // (Fable's among them) blink out on that poll and back on the next. See `mergedOver`.
                lastUsage = report.mergedOver(lastUsage)
                // BOTH surfaces, or they disagree. `lastUsage` feeds the dashboard bars (pushSession) AND the
                // composer's usage dots (pushMetaState → stateJson). Pushing only the dashboard left the dots
                // blank until some unrelated state change happened to re-push — so the same number appeared in
                // one place immediately and in the other "a while later", which reads as a broken readout.
                pushSession()
                pushMetaState()
            }
        }
    }

    /** Fetch MCP server status asynchronously and hand the raw payload to the dashboard's MCP health card. */
    private fun requestMcp() {
        session.requestMcpStatus { json ->
            if (json != null) host.exec("window.cc.mcp && window.cc.mcp(" + json + ")")
        }
    }

    /** Fetch the CLI binary version once and cache it on the session so the dashboard's Version row populates. */
    private fun requestVersion() {
        if (session.binaryVersion != null) return
        session.requestBinaryVersion { payload ->
            val v = payload?.let {
                it["version"]?.jsonPrimitive?.contentOrNull
                    ?: it["binary_version"]?.jsonPrimitive?.contentOrNull
                    ?: it["claude_code_version"]?.jsonPrimitive?.contentOrNull
            }
            if (!v.isNullOrBlank()) {
                session.binaryVersion = v
                pushSession()
            }
        }
    }

    /** Force a full transcript resend on the next tick (used on init and on a late page `Ready`). */
    private fun fullResync() {
        structural = true
        ensureTimer()
    }

    /**
     * Ctrl+V: read the system clipboard host-side (reliable on Wayland) on a POOLED thread, then apply on the EDT.
     * The Wayland fallback shells out to `wl-paste`/`xclip` and reads their stdout with a deadline — doing that on
     * the EDT (as before) froze the IDE whenever the clipboard owner was slow/hung. Image → attach; else text →
     * insert at the caret.
     */
    private fun pasteFromClipboardOffEdt() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val img = EditorContextProvider.imageFromClipboard()
            val text = if (img == null) EditorContextProvider.clipboardText() else null
            val help = if (img == null && text.isNullOrEmpty()) EditorContextProvider.clipboardImageHelp() else null
            ApplicationManager.getApplication().invokeLater({
                when {
                    img != null -> addAttachment(img)

                    !text.isNullOrEmpty() ->
                        host.exec("window.cc.insertText && window.cc.insertText(" + JsonPrimitive(text).toString() + ")")

                    else -> notifyClipboard(
                        if (help != null) "Couldn't read the clipboard — $help" else "Clipboard is empty or unreadable.",
                    )
                }
            }, ModalityState.any())
        }
    }

    /** Explicit "Paste image" / image-only Ctrl+V — same off-EDT read, image-only handling. */
    private fun pasteImageFromClipboardOffEdt(notify: Boolean) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val img = EditorContextProvider.imageFromClipboard()
            val shouldNotify = img == null && (notify || !EditorContextProvider.clipboardHasText())
            val help = if (shouldNotify) EditorContextProvider.clipboardImageHelp() else null
            ApplicationManager.getApplication().invokeLater({
                when {
                    img != null -> addAttachment(img)

                    shouldNotify -> notifyClipboard(
                        if (help != null) {
                            "Couldn't read an image from the clipboard — $help"
                        } else {
                            "No image found in the clipboard."
                        },
                    )
                }
            }, ModalityState.any())
        }
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
        ClaudeSettings.getInstance(project).rememberToolAlwaysAllow(m.tool)
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

        is JcefBridge.Msg.Open -> openUrl(m.url)

        is JcefBridge.Msg.ResolveLinks -> resolveLinksOffEdt(m)
    }

    private fun onAttachments(m: JcefBridge.Msg.Attachments) = when (m) {
        is JcefBridge.Msg.RemoveAttachment -> {
            attachments.remove(m.id)
            pushAttachments()
        }

        JcefBridge.Msg.PickFiles -> FilePickerHelper.chooseFiles(project).forEach {
            addAttachment(Attachment.FileRef(it, FilePickerHelper.displayName(project, it)))
        }

        JcefBridge.Msg.PickDirectory -> {
            FilePickerHelper.chooseDirectory(project)?.let {
                addAttachment(Attachment.FileRef(it, FilePickerHelper.displayName(project, it)))
            }
            Unit
        }

        JcefBridge.Msg.AttachSelection -> {
            EditorContextProvider.selectionAsAttachment(project)?.let { addAttachment(it) }
            Unit
        }

        JcefBridge.Msg.AttachCurrentFile -> mentionCurrentFile()

        JcefBridge.Msg.RequestAttachData -> pushAttachData()

        is JcefBridge.Msg.AttachPath ->
            addAttachment(Attachment.FileRef(m.path, FilePickerHelper.displayName(project, m.path)))

        JcefBridge.Msg.PasteClipboard -> pasteFromClipboardOffEdt()

        is JcefBridge.Msg.PasteClipboardImage -> pasteImageFromClipboardOffEdt(m.notify)

        is JcefBridge.Msg.Attach -> addAttachment(Attachment.Image(m.name, m.mediaType, m.base64))
    }

    private fun onSessionControl(m: JcefBridge.Msg.SessionControl) = when (m) {
        is JcefBridge.Msg.McpReconnect -> {
            session.reconnectMcp(m.name)
            requestMcp()
        }

        is JcefBridge.Msg.McpToggle -> {
            session.toggleMcp(m.name, m.enabled)
            requestMcp()
        }

        is JcefBridge.Msg.StopTask -> session.stopTask(m.taskId)

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
            pushAttachments()
            pushSession()
            requestMcp()
            requestVersion()
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
        val atts = attachments.values.toList()
        attachments.clear()
        pushAttachments()
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
     * Only **https** links open externally — plain http (a common malware-hosting scheme) is refused, and
     * file:/jar:/javascript: never reach here. Internal `jb://open?file=&line=` links jump to code in the
     * editor, gated to the project root. Links from the untrusted view are strictly gated.
     */
    private fun openUrl(url: String) {
        val u = url.trim()
        when {
            u.lowercase().startsWith("https://") -> BrowserUtil.browse(u)

            u.startsWith("jb://open") -> openJbLink(u)

            // A markdown link whose href is a PATH rather than a URL — `[BACKLOG](docs/BACKLOG.md)`. It carries
            // no scheme, so it matched neither branch above and the click did NOTHING: no navigation, no error,
            // nothing in any log. Bare paths written in prose already resolve (LinkResolver confirms them before
            // linking), which made this the odd one out — the more deliberate the link, the less it worked.
            //
            // The scheme test is what keeps this from swallowing the other schemes DOMPurify allows (`mailto:`,
            // `tel:`, `sms:`…): anything with a scheme is not a path, and is still ignored here as before.
            LinkResolver.isFilePathHref(u) -> openPath(u.substringBefore('#').trim())
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

    /**
     * Turns a link's `file` param into an absolute path: `~/…` is expanded, an absolute path is taken as-is, and a
     * relative one is resolved against the project root. Returns null when a relative path has no root to resolve
     * against. The caller still gates the result with [LinkResolver.isOpenable] — this only *builds* the path, it
     * does not authorise it.
     */
    private fun resolveAgainstRoot(raw: String): String? {
        val f = java.io.File(LinkResolver.expandHome(raw))
        if (f.isAbsolute) return f.path
        val root = project.basePath ?: return null
        return java.io.File(root, f.path).path
    }

    /** Opens the file from a `jb://open?file=<encoded-path>&line=N` link in the editor, gated by [LinkResolver]. */
    private fun openJbLink(url: String) {
        val query = url.substringAfter('?', "")
        val params = query.split('&').mapNotNull {
            val k = it.substringBefore('=', "")
            val v = it.substringAfter('=', "")
            if (k.isEmpty()) null else k to runCatching { java.net.URLDecoder.decode(v, Charsets.UTF_8) }.getOrDefault(v)
        }.toMap()
        val raw = params["file"] ?: return
        openPath(raw, (params["line"]?.toIntOrNull() ?: 1))
    }

    /**
     * Opens [raw] — project-relative or absolute — in the editor, or reveals it in the tree when it is a
     * directory or an archive. The single authorising gate for every link the transcript can produce.
     *
     * A link normally carries a PROJECT-RELATIVE path; one pointing into the user's home carries an absolute
     * one. Either way this only *builds* the path — [LinkResolver.isOpenable] is what authorises it, and it is
     * the one place that decides, so neither a hand-crafted `jb://` URL nor a markdown href can reach a file we
     * would not have linked ourselves.
     */
    private fun openPath(raw: String, line: Int = 1) {
        val path = resolveAgainstRoot(raw) ?: return
        if (!LinkResolver.isOpenable(path, project.basePath)) return // project or the user's own home, nothing else
        // refreshAndFind, not find: a file Claude has just written may not be in the VFS yet, and a plain lookup
        // would return null — the link would silently do nothing until the IDE next refreshed. (The session also
        // refreshes on every successful write; this is the belt to that pair of braces.)
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: return
        // A directory — and an ARCHIVE, which has no meaningful editor either (opening `foo.zip` would just show a
        // binary buffer) — belong in the tree, not in an editor tab. Revealing them there is the useful action:
        // you can then right-click → Copy full path, Open in Files, expand the archive…
        if (vf.isDirectory || vf.fileType is ArchiveFileType) {
            revealDirectory(vf)
            return
        }
        com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vf, line.coerceAtLeast(1) - 1, 0).navigate(true)
        selectInProjectView(vf)
    }

    /**
     * Mirrors *Autoscroll from Source*: the opened file is also selected in the **Project view**, so you can see
     * where it lives. Deliberately unobtrusive — `requestFocus = false` keeps the caret in the editor you just
     * jumped into, and the tool window is NOT force-opened: if you keep the tree hidden, a link click has no
     * business popping it open. A file outside the project (in the home) simply isn't in the tree, so we skip it.
     */
    private fun selectInProjectView(file: com.intellij.openapi.vfs.VirtualFile) {
        if (!DiffPresenter.isWithinRoot(file.path, project.basePath)) return
        val tw = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.PROJECT_VIEW) ?: return
        if (!tw.isVisible) return
        runCatching { ProjectView.getInstance(project).select(null, file, false) }
    }

    /**
     * Reveals something that has no editor to open (a directory, an archive). Inside the project it belongs to the
     * **Project view** — select and expand it there, activating the tool window: unlike the file case, selecting
     * into a hidden tree would make the click look like it did nothing at all. Outside the project (in the user's
     * home) it isn't in the tree, so the only sensible target is the OS file manager. Already gated by
     * [LinkResolver.isOpenable] before we get here.
     */
    private fun revealDirectory(target: com.intellij.openapi.vfs.VirtualFile) {
        if (DiffPresenter.isWithinRoot(target.path, project.basePath)) {
            val select = { ProjectView.getInstance(project).select(null, target, true) }
            val tw = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.PROJECT_VIEW)
            if (tw != null) tw.activate(select, true) else select()
        } else {
            RevealFileAction.openDirectory(java.io.File(target.path))
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
    fun mentionCurrentFile() {
        val path = EditorContextProvider.currentFilePath(project) ?: return
        addAttachment(Attachment.FileRef(path, FilePickerHelper.displayName(project, path)))
    }

    /** Pins an attachment (file / selection / image) to the next turn as a chip; it travels with the next send. */
    fun addAttachment(attachment: Attachment) {
        attachments["a" + (nextAttachmentId++)] = attachment
        pushAttachments()
        focusInput()
    }

    private fun pushAttachments() {
        host.exec("window.cc.attachments && window.cc.attachments(" + attachmentsJson() + ")")
    }

    /** Data for the rich 📎 attach menu: recent files (newest-first) + what context is available right now. */
    private fun pushAttachData() {
        val recent = FilePickerHelper.recentFiles(project, RECENT_FILES_LIMIT).map { path ->
            buildJsonObject {
                put("path", path)
                put("name", FilePickerHelper.displayName(project, path))
                put("ext", path.substringAfterLast('.', "").lowercase())
            }
        }
        val payload = buildJsonObject {
            put("recent", JsonArray(recent))
            put("hasSelection", EditorContextProvider.currentSelection(project) != null)
            put("hasFile", EditorContextProvider.currentFilePath(project) != null)
        }
        host.exec("window.cc.attachData && window.cc.attachData($payload)")
    }

    private fun attachmentsJson(): String = JsonArray(
        attachments.map { (id, a) ->
            buildJsonObject {
                put("id", id)
                put("label", a.displayName)
                put(
                    "kind",
                    when (a) {
                        is Attachment.Image -> "image"
                        is Attachment.Selection -> "selection"
                        is Attachment.FileRef -> "file"
                    },
                )
            }
        },
    ).toString()

    override fun dispose() {
        livePanels.remove(this)
        session.transcript.removeListener(this)
        session.removeListener(this)
        session.detachLoginUi(onboarding)
        onboarding.dispose()
        timer.stop()
        usageTimer.stop()
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

        /** How many recently-opened files the attach menu offers before the user has to search. */
        private const val RECENT_FILES_LIMIT = 14

        /** Period of the plan-limits poll, visible or not — a window reset happens on wall-clock time. */
        private const val USAGE_POLL_MS = 30_000

        /**
         * Floor between `get_usage` round-trips — burst protection for the event-driven triggers. MUST stay
         * below [USAGE_POLL_MS], or the periodic tick is silently throttled away and the poll only *looks*
         * like it runs on its period.
         */
        private const val USAGE_MIN_INTERVAL_MS = 12_000L

        // Vibe Mode is global (ChatTheme.vibeMode), so a toggle on one tab must re-theme them all.
        private val livePanels = java.util.concurrent.CopyOnWriteArrayList<JcefChatPanel>()
        fun broadcastTheme() {
            livePanels.forEach { it.pushTheme() }
        }
    }
}
