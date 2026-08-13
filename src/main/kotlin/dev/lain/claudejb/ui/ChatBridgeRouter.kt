package dev.lain.claudejb.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import dev.lain.claudejb.context.FilePickerHelper
import dev.lain.claudejb.context.ImageAttachments
import dev.lain.claudejb.diff.DiffPresenter
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.Provider
import dev.lain.claudejb.ui.jcef.JcefBridge
import dev.lain.claudejb.ui.jcef.JcefTranscriptPayload
import java.awt.datatransfer.StringSelection

/**
 * Everything the web app sends back, routed to whoever owns it.
 *
 * Extracted from `JcefChatPanel`, which is an assembler: this is its inbound half — the dispatch table — and
 * it holds no state of its own. It runs on the EDT, where [dev.lain.claudejb.ui.jcef.JcefHost] delivers the
 * messages, and it reaches the panel's collaborators (the session, the tray, the feed, the tab bar, the
 * onboarding cards) rather than re-implementing any of them.
 */
internal class ChatBridgeRouter(private val panel: JcefChatPanel) {

    private val session: ClaudeSession get() = panel.session
    private val tray: AttachmentTray get() = panel.tray
    private val feed: SessionFeed get() = panel.feed

    /**
     * Inbound dispatch, in two levels: pick the message group, then the message. The groups are declared on
     * [JcefBridge.Msg] and mirror the bridge's own parsers, so a message is parsed and handled by the same
     * concern — and the compiler still checks exhaustiveness at both levels, so adding a message type without
     * handling it does not compile.
     */
    fun dispatch(json: String) {
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
            JcefChatPanel.broadcastTheme()
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
        ClaudeSettings.getInstance(panel.project).alwaysAllow.remember(m.tool)
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
                ?.let { DiffPresenter.openDiff(panel.project, it.toolName, it.input) }
            Unit
        }

        is JcefBridge.Msg.ViewDiffByTool -> {
            // Completed edit: open the native diff from the captured pre-write snapshot.
            session.editSnapshot(m.toolUseId)?.let {
                DiffPresenter.openDiff(panel.project, it.toolName, it.input, it.beforeText)
            }
            Unit
        }

        is JcefBridge.Msg.RevertEdit -> panel.edits.rewindOrRevert(m.toolUseId)

        JcefBridge.Msg.OpenDiffHistory -> ClaudeToolWindowFactory.openDiffHistoryFor(panel.project, session)

        is JcefBridge.Msg.Open -> panel.links.open(m.url)

        is JcefBridge.Msg.ResolveLinks -> resolveLinksOffEdt(m)
    }

    private fun onAttachments(m: JcefBridge.Msg.Attachments) = when (m) {
        is JcefBridge.Msg.RemoveAttachment -> tray.remove(m.id)

        JcefBridge.Msg.PickFiles -> FilePickerHelper.chooseFiles(panel.project).forEach(tray::addPath)

        JcefBridge.Msg.PickDirectory -> {
            FilePickerHelper.chooseDirectory(panel.project)?.let(tray::addPath)
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

        is JcefBridge.Msg.Attach -> onAttachImage(m)
    }

    /**
     * An image the web app is offering (drag&drop / paste in the composer).
     *
     * This is a TRUST BOUNDARY, so nothing in the message is taken at its word: the declared `mediaType` is the
     * renderer's guess from a file extension, and the base64 is whatever the page put there. Building the
     * attachment straight from the three fields — which is what this did — sent an unbounded, unsniffed payload
     * to the binary as an image content block. Validation lives in [ImageAttachments.fromWebPayload] (pure,
     * unit-tested); this is the one line that calls it.
     *
     * **Rejected, never truncated.** A cut-off image is a corrupt image: the model would be handed garbage bytes
     * and answer about them, which is worse than not attaching at all. The refusal is spoken in the transcript
     * because a chip that silently fails to appear reads as the drop having missed the composer.
     */
    private fun onAttachImage(m: JcefBridge.Msg.Attach) {
        val image = ImageAttachments.fromWebPayload(m.name, m.mediaType, m.base64)
        if (image == null) {
            tray.notify(
                "That attachment was not added: only PNG, JPEG, GIF and WebP images are accepted, " +
                    "up to ${ImageAttachments.MAX_IMAGE_BYTES / BYTES_PER_MB} MB.",
            )
            return
        }
        tray.add(image)
    }

    private fun onSessionControl(m: JcefBridge.Msg.SessionControl) = when (m) {
        is JcefBridge.Msg.McpReconnect -> {
            session.queries.reconnectMcp(m.name)
            feed.requestMcp()
        }

        is JcefBridge.Msg.McpToggle -> {
            session.queries.toggleMcp(m.name, m.enabled)
            feed.requestMcp()
        }

        is JcefBridge.Msg.StopTask -> session.queries.stopTask(m.taskId)

        // The transcript card (and the dashboard lists) asking to go to an agent's tab. Reopens it when the
        // user had closed it: closing hides a view, it never removes the agent or its transcript.
        //
        // With nothing to resolve it means the CHAT's own transcript — a background task the binary never
        // attributed to an agent still ran somewhere, and that somewhere is this chat.
        is JcefBridge.Msg.RevealAgent -> panel.agentTabs.revealElsewhere(m.chatId) { it.agentTabs.revealFromHost(m) }

        is JcefBridge.Msg.RevealBackgroundTask ->
            panel.agentTabs.revealElsewhere(m.chatId) { it.transcript.showBackgroundTask(m.taskId) }

        // The tab bar lives in the page, so its clicks arrive here like any other web→host message.
        is JcefBridge.Msg.SelectChat -> panel.chatStrip()?.selectById(m.chatId)

        is JcefBridge.Msg.CloseChat -> panel.chatStrip()?.closeById(m.chatId)

        // No id means the chat's own transcript: that is how the breadcrumb's first segment goes back, and
        // `Shown.Agent("")` would be a transcript for an agent that does not exist — an empty page.
        is JcefBridge.Msg.SelectAgent -> panel.transcript.showTranscript(m.agentId.ifBlank { null })

        // Pinning is the strip's business: it owns the tabs. This panel only knows WHAT was pinned.
        is JcefBridge.Msg.PinSubtab -> panel.agentTabs.pinSubtab(m)

        is JcefBridge.Msg.CloseAgent -> panel.agentTabs.closeAgent(m.agentId)

        // Everything the two onboarding cards send (install / binary path / sign-in / logout) lives in
        // its own collaborator — see OnboardingController. `handle` returns false only for messages that
        // are not onboarding's, and every remaining SessionControl IS handled above, so falling through
        // here means a new message was added without a handler: surface it instead of ignoring it.
        else -> {
            val handled = panel.onboarding.handle(m)
            if (!handled) logger.warn("unhandled session-control message: $m")
            Unit
        }
    }

    private fun onLifecycle(m: JcefBridge.Msg.Lifecycle) = when (m) {
        JcefBridge.Msg.Ready -> {
            panel.host.markWebReady() // the web app is alive — cancel the first-open self-heal watchdog
            panel.pushTheme()
            panel.pushMetaState()
            panel.pushPermissions()
            tray.push()
            panel.pushSession()
            feed.requestMcp()
            feed.requestVersion()
            panel.transcript.fullResync()
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
        val project = panel.project
        ApplicationManager.getApplication().executeOnPooledThread {
            val resolved = runCatching {
                LinkResolver.resolvePaths(project, m.paths) + LinkResolver.resolveSymbols(project, m.symbols)
            }.getOrDefault(emptyList())
            if (resolved.isEmpty()) return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                panel.host.exec(
                    "window.cc.links && window.cc.links(" + JcefTranscriptPayload.linksJson(m.rowId, resolved) + ")",
                )
            }, ModalityState.any())
        }
    }

    private companion object {
        private val logger = Logger.getInstance(ChatBridgeRouter::class.java)

        private val BTW = Regex("^/btw\\b.*")

        /** For wording the attachment cap in the unit the user set it in. */
        private const val BYTES_PER_MB = 1024 * 1024
    }
}
