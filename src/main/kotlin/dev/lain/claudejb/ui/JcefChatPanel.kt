package dev.lain.claudejb.ui

import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBPanel
import dev.lain.claudejb.context.Attachment
import dev.lain.claudejb.context.EditorContextProvider
import dev.lain.claudejb.context.FilePickerHelper
import dev.lain.claudejb.diff.DiffPresenter
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
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import javax.swing.Timer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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

    private val host = JcefHost(this, ::onBridgeMessage)

    // ── Streaming coalescer state (all touched on the EDT) ───────────────────────────────────────────────
    private val dirty = LinkedHashSet<Long>()
    private var structural = false
    private val timer = Timer(30) { onTick() }.apply { isRepeats = true }

    // ── Pending attachments pinned to the next turn (editor actions, drag/drop/paste, file picker) ────────
    private val attachments = LinkedHashMap<String, Attachment>()
    private var nextAttachmentId = 0L

    init {
        background = ChatTheme.BG
        add(host.component, BorderLayout.CENTER)

        livePanels.add(this)
        session.transcript.addListener(this)
        session.addListener(this)

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
        requestMcp()
        requestVersion()
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

    override fun onStateChanged() { pushMetaState(); pushSession() }
    override fun onMetadataChanged() { pushMetaState(); pushSession() }
    override fun onPermissionsChanged() = pushPermissions()
    override fun onAttention(reason: AttentionReason) {}
    override fun onTitleChanged() {}

    // ── Push helpers ─────────────────────────────────────────────────────────────────────────────────────

    private fun pushTheme() {
        host.exec("window.cc.theme && window.cc.theme(" + JcefTheme.vars() + ")")
    }

    private fun pushMetaState() {
        host.exec(
            "window.cc.meta && window.cc.meta(" + JcefState.metaJson(session) + ");" +
                "window.cc.state && window.cc.state(" + JcefState.stateJson(session) + ")"
        )
    }

    private fun pushPermissions() {
        val perms = session.pendingPermissions()
        val diffByRequest = computeDiffs(perms)
        host.exec(
            "window.cc.permissions && window.cc.permissions(" +
                JcefBridge.permissionsJson(perms, diffByRequest) + ")"
        )
    }

    /**
     * For each reviewable Edit/Write/MultiEdit permission, compute a read-only unified diff (current vs proposed)
     * so the card can show what's changing in red/green. Edits are accepted/rejected as a whole — there is no
     * per-line selection (it produced incoherent, broken code).
     */
    private fun computeDiffs(perms: List<dev.lain.claudejb.permission.PendingPermission>): Map<String, String> {
        val out = HashMap<String, String>()
        for (p in perms) {
            if (!p.reviewable || p.toolName !in DiffPresenter.REVIEWABLE_TOOLS) continue
            val path = DiffPresenter.filePathOf(p.input) ?: continue
            // Cap the synchronous (EDT) disk read + diff: a multi-MB file would freeze the UI, and an inline diff
            // is meaningless at that size. Oversized files skip the inline diff (View diff still works); a normal
            // accept/reject is unaffected (the binary does its own read/write).
            val file = java.io.File(path)
            if (file.isFile && file.length() > MAX_HUNK_FILE_BYTES) continue
            val current = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull() ?: ""
            val proposed = DiffPresenter.proposedContent(p.toolName, p.input, current) ?: continue
            val diff = DiffPresenter.unifiedDiff(current, proposed).takeIf { it.isNotBlank() } ?: continue
            out[p.requestId] = diff
        }
        return out
    }

    /** Push the session-dashboard data (context categories, cost, account, subagents) to the web view. */
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
                        } else offerIdeFallback(snap, done?.error ?: "rewind failed")
                    }
                } else offerIdeFallback(snap, probe?.error ?: "no checkpoint for this turn")
            }
        } else {
            offerIdeFallback(snap, if (!session.checkpointingEnabled) "checkpointing disabled" else "no turn anchor for this edit")
        }
    }

    /** Confirmation (with a remembered choice) to fall back to the IDE-side per-file revert. */
    private fun offerIdeFallback(snap: dev.lain.claudejb.diff.EditSnapshot?, reason: String) {
        if (snap == null) { notifyClipboard("Nothing to restore for this edit."); return }
        val settings = ClaudeSettings.getInstance(project)
        when (settings.rewindFallback) {
            "ide" -> { session.revertEdit(snap); return }
            "never" -> { notifyClipboard("Native rewind unavailable ($reason)."); return }
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
        pushSession(); requestMcp(); requestVersion()
        host.exec("window.cc.openDashboard && window.cc.openDashboard()")
    }

    private fun pushSession() {
        host.exec("window.cc.session && window.cc.session(" + JcefSessionData.sessionJson(session) + ")")
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
            if (!v.isNullOrBlank()) { session.binaryVersion = v; pushSession() }
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
                        if (help != null) "Couldn't read an image from the clipboard — $help"
                        else "No image found in the clipboard.",
                    )
                }
            }, ModalityState.any())
        }
    }

    // ── Inbound dispatch (EDT) ───────────────────────────────────────────────────────────────────────────

    private fun onBridgeMessage(json: String) {
        when (val m = JcefBridge.parse(json)) {
            is JcefBridge.Msg.Send -> dispatchSend(m.text)
            JcefBridge.Msg.Interrupt -> session.interrupt()
            JcefBridge.Msg.CycleMode -> session.cyclePermissionMode()
            is JcefBridge.Msg.ChangeModel -> session.changeModel(m.value)
            is JcefBridge.Msg.ChangeMode -> session.changePermissionMode(m.wire)
            is JcefBridge.Msg.ChangeEffort -> session.changeEffort(m.value)
            is JcefBridge.Msg.ChangeThinking ->
                session.changeThinkingTokens(if (m.on) ClaudeSession.THINKING_ON else null)
            is JcefBridge.Msg.ChangeVibe -> { ChatTheme.setVibeMode(m.on); broadcastTheme() }
            is JcefBridge.Msg.ChangeProvider -> session.changeProvider(Provider.fromId(m.id))
            is JcefBridge.Msg.RemoveQueued -> session.removeQueued(m.index)
            // Edits are atomic: accept or reject the whole change (no per-line selection — it broke code coherence).
            is JcefBridge.Msg.ResolvePermission -> session.resolvePermission(m.id, m.allow)
            is JcefBridge.Msg.ResolveQuestion -> session.resolveQuestion(m.id, m.answers)
            is JcefBridge.Msg.AlwaysAllow -> {
                ClaudeSettings.getInstance(project).rememberToolAlwaysAllow(m.tool)
                // Resolve THE card the button lives on (by requestId), not just the first pending card with that
                // tool name — with two pending Bash cards, "Always allow" on the second used to approve (and run)
                // the first, unseen command. Fall back to tool-name match only if the id didn't come through.
                val pending = session.pendingPermissions()
                val target = pending.firstOrNull { it.requestId == m.id }
                    ?: pending.firstOrNull { it.toolName == m.tool }
                target?.let { session.resolvePermission(it.requestId, true) }
            }
            is JcefBridge.Msg.ViewDiff -> {
                session.pendingPermissions().firstOrNull { it.requestId == m.id }
                    ?.let { DiffPresenter.openDiff(project, it.toolName, it.input) }
            }
            is JcefBridge.Msg.ViewDiffByTool -> {
                // Completed edit: open the native diff from the captured pre-write snapshot.
                session.editSnapshot(m.toolUseId)?.let {
                    DiffPresenter.openDiff(project, it.toolName, it.input, it.beforeText)
                }
            }
            is JcefBridge.Msg.RevertEdit -> rewindOrRevert(m.toolUseId)
            JcefBridge.Msg.OpenDiffHistory -> ClaudeToolWindowFactory.openDiffHistoryFor(project, session)
            is JcefBridge.Msg.ResolveElicitation -> session.resolveElicitation(m.id, m.action, m.content)
            is JcefBridge.Msg.Open -> openUrl(m.url)
            is JcefBridge.Msg.Copy -> CopyPasteManager.getInstance().setContents(StringSelection(m.text))
            is JcefBridge.Msg.RemoveAttachment -> { attachments.remove(m.id); pushAttachments() }
            JcefBridge.Msg.PickFiles -> FilePickerHelper.chooseFiles(project).forEach {
                addAttachment(Attachment.FileRef(it, FilePickerHelper.displayName(project, it)))
            }
            JcefBridge.Msg.PickDirectory -> FilePickerHelper.chooseDirectory(project)?.let {
                addAttachment(Attachment.FileRef(it, FilePickerHelper.displayName(project, it)))
            }
            JcefBridge.Msg.AttachSelection ->
                EditorContextProvider.selectionAsAttachment(project)?.let { addAttachment(it) }
            JcefBridge.Msg.AttachCurrentFile -> mentionCurrentFile()
            JcefBridge.Msg.RequestAttachData -> pushAttachData()
            is JcefBridge.Msg.AttachPath ->
                addAttachment(Attachment.FileRef(m.path, FilePickerHelper.displayName(project, m.path)))
            JcefBridge.Msg.PasteClipboard -> pasteFromClipboardOffEdt()
            is JcefBridge.Msg.PasteClipboardImage -> pasteImageFromClipboardOffEdt(m.notify)
            is JcefBridge.Msg.Attach -> addAttachment(Attachment.Image(m.name, m.mediaType, m.base64))
            is JcefBridge.Msg.McpReconnect -> { session.reconnectMcp(m.name); requestMcp() }
            is JcefBridge.Msg.McpToggle -> { session.toggleMcp(m.name, m.enabled); requestMcp() }
            is JcefBridge.Msg.StopTask -> session.stopTask(m.taskId)
            JcefBridge.Msg.Ready -> {
                host.markWebReady() // the web app is alive — cancel the first-open self-heal watchdog
                pushTheme(); pushMetaState(); pushPermissions(); pushAttachments(); pushSession(); requestMcp(); requestVersion(); fullResync()
            }
            JcefBridge.Msg.OpenPalette -> {} // client-side overlay; nothing to do backend-side
            is JcefBridge.Msg.Unknown -> {} // total dispatch, ignore
        }
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
        }
    }

    /** Opens the file from a `jb://open?file=<encoded-abs>&line=N` link in the editor, gated to the root. */
    private fun openJbLink(url: String) {
        val query = url.substringAfter('?', "")
        val params = query.split('&').mapNotNull {
            val k = it.substringBefore('=', ""); val v = it.substringAfter('=', "")
            if (k.isEmpty()) null else k to runCatching { java.net.URLDecoder.decode(v, Charsets.UTF_8) }.getOrDefault(v)
        }.toMap()
        val path = params["file"] ?: return
        if (!DiffPresenter.isWithinRoot(path, project.basePath)) return // never escape the project root
        val line = (params["line"]?.toIntOrNull() ?: 1).coerceAtLeast(1) - 1
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path) ?: return
        com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vf, line, 0).navigate(true)
    }

    // ── Tool-window actions ──────────────────────────────────────────────────────────────────────────────

    fun focusInput() = host.exec("window.cc.focusInput && window.cc.focusInput()")

    fun showCommandPalette() = host.exec("window.cc.openPalette && window.cc.openPalette()")

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
        val recent = FilePickerHelper.recentFiles(project, 14).map { path ->
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
        timer.stop()
        // host disposes via the parentDisposable (this panel) registered in JcefHost.
    }

    private companion object {
        private val BTW = Regex("^/btw\\b.*")

        // Files larger than this skip the EDT-side hunk read/diff for hunk-by-hunk review (full accept still works).
        private const val MAX_HUNK_FILE_BYTES = 1_000_000L

        // Vibe Mode is global (ChatTheme.vibeMode), so a toggle on one tab must re-theme them all.
        private val livePanels = java.util.concurrent.CopyOnWriteArrayList<JcefChatPanel>()
        fun broadcastTheme() { livePanels.forEach { it.pushTheme() } }
    }
}
