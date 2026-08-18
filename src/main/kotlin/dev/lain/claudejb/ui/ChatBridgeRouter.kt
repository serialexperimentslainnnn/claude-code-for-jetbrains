package dev.lain.claudejb.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import dev.lain.claudejb.context.ImageAttachments
import dev.lain.claudejb.context.ProjectTree
import dev.lain.claudejb.diff.DiffPresenter
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.WorkloadWindow
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.Provider
import dev.lain.claudejb.ui.jcef.JcefBridge
import dev.lain.claudejb.ui.jcef.JcefSettingsMenu
import dev.lain.claudejb.ui.jcef.JcefTranscriptPayload
import dev.lain.claudejb.ui.jcef.JcefTreeData
import kotlinx.serialization.json.JsonObject
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
        // The composer serves whichever conversation is on screen: this chat, or the one embedded in the Git
        // view. A Git-scoped turn skips `dispatchSend`'s suggestion/attachment bookkeeping deliberately —
        // that state belongs to this panel's composer and its own session, not to a turn about the repository.
        is JcefBridge.Msg.Send ->
            if (m.scope == JcefBridge.SCOPE_GIT) panel.gitChat.send(m.text) else dispatchSend(m.text)

        is JcefBridge.Msg.Interrupt ->
            if (m.scope == JcefBridge.SCOPE_GIT) panel.gitChat.interrupt() else session.interrupt()

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

        is JcefBridge.Msg.SettingsToggle -> onSettingsToggle(m)

        JcefBridge.Msg.OpenSettings ->
            ShowSettingsUtil.getInstance().showSettingsDialog(panel.project, ClaudeSettingsConfigurable::class.java)
    }

    /**
     * One switch of the composer's ⚙ menu.
     *
     * An unknown key is LOGGED rather than dropped in silence — six of these switches are the deterministic
     * guard's own rules and one pre-authorises a tool, so a key that stops matching after a rename is a
     * security control the user can press and nothing answers. And the menu is re-pushed to EVERY chat: these
     * settings are global, so leaving the other pages showing the old state would make the same switch read
     * differently depending on which tab you opened it from.
     */
    private fun onSettingsToggle(m: JcefBridge.Msg.SettingsToggle) {
        if (!writeSettingsToggle(m)) {
            logger.warn("The chat's settings menu asked for a switch this build does not have: ${m.key}")
            return
        }
        JcefChatPanel.pushSettingsMenuToAll()
    }

    /**
     * Writes one switch, and answers false when no destination claims its key.
     *
     * Three destinations, because a menu row is not always a field of the settings document, and each of the
     * other two carries a rule that a single `update` block would break:
     *
     * The **"Always allow" set** owns its own persistence ([dev.lain.claudejb.settings.AlwaysAllowTools]),
     * which opens an `update` of its own — so it is answered before this method opens one, never from inside
     * it. Nesting a read-modify-write of the settings document inside another is the exact race the serialised
     * writes exist to remove.
     *
     * The **document** is written through `update {}`, never a bare `state.x = y`, which is a change that
     * silently does not survive a restart. The block is applied twice by design, so what goes in it is the
     * pure delta and nothing else — hence the model catalogue being read out here rather than in there.
     *
     * The **live session** is driven afterwards, and only for the three rows the composer also has a pill for:
     * a menu that showed the stored default while the pill showed the running value would let two controls
     * answer the same question differently.
     */
    private fun writeSettingsToggle(m: JcefBridge.Msg.SettingsToggle): Boolean {
        val settings = ClaudeSettings.getInstance(panel.project)
        JcefSettingsMenu.alwaysAllowTool(m.key)?.let { tool ->
            if (m.on) settings.alwaysAllow.remember(tool) else settings.alwaysAllow.forget(tool)
            return true
        }
        val models = session.models.map { it.value }
        var known = false
        settings.update { known = JcefSettingsMenu.apply(it, m.key, m.on, models) }
        if (known) JcefSettingsMenu.applyToSession(session, m.key, m.on)
        return known
    }

    private fun onRequestCard(m: JcefBridge.Msg.RequestCard) = when (m) {
        // Edits are atomic: accept or reject the whole change (no per-line selection — it broke code coherence).
        is JcefBridge.Msg.ResolvePermission -> onResolvePermission(m)

        is JcefBridge.Msg.ResolveQuestion -> cardSession(m.scope).resolveQuestion(m.id, m.answers)

        is JcefBridge.Msg.ResolveElicitation ->
            cardSession(m.scope).resolveElicitation(m.id, m.action, m.content)

        is JcefBridge.Msg.AlwaysAllow -> onAlwaysAllow(m)
    }

    /**
     * The conversation a request card belongs to.
     *
     * Two of them reach this page: the chat it was built for, and the one embedded in the Git view, whose
     * cards are drawn by the same renderer into a container of its own. The card says which
     * ([JcefBridge.SCOPE_GIT]) rather than the host matching the request id against both and answering
     * whichever recognises it — on a collision that approves a `git` command in the wrong conversation, and
     * nothing on screen would say so.
     */
    private fun cardSession(scope: String): ClaudeSession =
        if (scope == JcefBridge.SCOPE_GIT) panel.gitChat.session() else session

    /** Runs [block] against the chat strip, and logs [what] instead of doing nothing when there is none. */
    private fun withStrip(what: String, block: (ChatTabsPanel) -> Unit) {
        val strip = panel.chatStrip()
        if (strip == null) {
            logger.warn("Claude Code: no chat strip to $what — the press was dropped")
            return
        }
        block(strip)
    }

    /**
     * Resolves a permission card, and re-reads the plan when the card WAS the plan.
     *
     * Approving an `ExitPlanMode` request is the exact instant a plan becomes final — a deliberate act by the
     * user, not something inferred from a tool name in the stream. Without this the Plan card only refreshed
     * at the end of the turn, so asking the agent to revise its plan left the old one on screen for as long
     * as the rest of the turn took, which reads as the card being broken.
     *
     * The flag has to be read BEFORE resolving: resolving removes the card.
     */
    private fun onResolvePermission(m: JcefBridge.Msg.ResolvePermission) {
        val target = cardSession(m.scope)
        val wasPlan = target.pendingPermissions().firstOrNull { it.requestId == m.id }?.isPlan == true
        target.resolvePermission(m.id, m.allow)
        // Only on approval: a rejected plan is not the session's plan, and the file the binary holds is
        // whatever it was before. And only for THIS panel's session — the dashboard's Plan card is fed from
        // it, so re-reading it after approving a plan in the Git conversation would ask the wrong process.
        if (wasPlan && m.allow && target === session) panel.feed.requestPlan()
    }

    /**
     * A button on the Git view. The runtime is [GitIntegration]; this is the one line that reaches it.
     *
     * **The chat a prompted action talks in is the Git conversation, never this panel's session.** The view
     * is drawn in ANY chat's dashboard, and this once resolved the target as "the existing Git chat, or else
     * the session showing the view": with no Git chat open — the common case, since nothing opened one on its
     * own — *Commit with Claude* wrote its whole turn into the conversation the user was having, which is the
     * single thing the separate conversation exists to prevent. [GitChatFeed.session] finds or creates it,
     * and starts it silently: no tab, so no full-window boot screen for a chat nobody asked to look at.
     *
     * **And the view goes to it.** Pressing a prompted action used to produce nothing visible at all — the
     * turn ran somewhere else, and the only sign was the tab badging itself eventually. The conversation is
     * one of this view's two destinations now, so switching to it IS the feedback: the prompt is on screen as
     * a row the moment it is sent, and the permission card that gates the command appears under it.
     *
     * `pushGit` on the way out: the repository has just moved, and the branch, the change list and the
     * button's own state are all read back from it.
     *
     * The hash rides straight through, unread: which entries act on a commit and whether the value is even a
     * commit hash are [GitActionCatalog]'s to say, and a second opinion formed here is one that can disagree
     * with the catalogue the button was drawn from.
     */
    private fun onGitAction(m: JcefBridge.Msg.GitAction) {
        val chat = { panel.gitChat.session() }
        GitIntegration.getInstance(panel.project).perform(m.id, m.hash, chat) { panel.pushGit() }
        // Only the prompted ones. `init` spawns `git` itself, the IDE ones open the platform's own dialog and
        // the two reads answer in place — sending the user to a conversation that was never asked anything
        // would be a destination change with nothing at the destination.
        if (GitActionCatalog.byId(m.id)?.kind == GitActionCatalog.Kind.PROMPT) panel.gitChat.show()
    }

    /**
     * The Workloads view's retention control: how long finished work stays listed.
     *
     * **The value is checked against the rule's own list rather than trusted**, for the reason the diagram
     * exists at all: a window nobody offered would be stored, applied, and then measured against a choice the
     * user never made — and the only symptom is that the view shows the wrong things, which is exactly the
     * kind of wrong that never gets reported as a bug.
     *
     * Written through `update {}`, never `state.x = y`: a bare assignment is a change that silently does not
     * survive a restart. Every open chat is re-pushed afterwards, because the window is global and the
     * diagram spans them — refreshing only this panel would leave the others drawing the old one.
     */
    private fun onSetWorkloadWindow(minutes: Int) {
        if (minutes !in WorkloadWindow.WINDOW_MINUTES) {
            logger.warn("Workloads view asked for a window this build does not offer: $minutes")
            return
        }
        ClaudeSettings.getInstance(panel.project).update { it.workloadWindowMinutes = minutes }
        JcefChatPanel.pushSessionToAll()
    }

    private fun onAlwaysAllow(m: JcefBridge.Msg.AlwaysAllow) {
        ClaudeSettings.getInstance(panel.project).alwaysAllow.remember(m.tool)
        // Resolve THE card the button lives on (by requestId), not just the first pending card with that
        // tool name — with two pending Bash cards, "Always allow" on the second used to approve (and run)
        // the first, unseen command. Fall back to tool-name match only if the id didn't come through.
        val chat = cardSession(m.scope)
        val pending = chat.pendingPermissions()
        val target = pending.firstOrNull { it.requestId == m.id }
            ?: pending.firstOrNull { it.toolName == m.tool }
        target?.let { chat.resolvePermission(it.requestId, true) }
    }

    private fun onDiffs(m: JcefBridge.Msg.Diffs) = when (m) {
        is JcefBridge.Msg.ViewDiff -> {
            cardSession(m.scope).pendingPermissions().firstOrNull { it.requestId == m.id }
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

        is JcefBridge.Msg.Open -> panel.links.open(m.url)

        is JcefBridge.Msg.ResolveLinks -> resolveLinksOffEdt(m)
    }

    private fun onAttachments(m: JcefBridge.Msg.Attachments) = when (m) {
        is JcefBridge.Msg.RemoveAttachment -> tray.remove(m.id)

        JcefBridge.Msg.AttachSelection -> {
            tray.addSelection()
            Unit
        }

        JcefBridge.Msg.AttachCurrentFile -> tray.addCurrentFile()

        JcefBridge.Msg.RequestAttachData -> tray.pushMenuData()

        is JcefBridge.Msg.AttachPath -> tray.addPath(m.path)

        is JcefBridge.Msg.TreeChildren -> onTreeChildren(m)

        is JcefBridge.Msg.TreeExpand -> onTreeExpand(m)

        is JcefBridge.Msg.AttachPaths -> onAttachPaths(m.paths)

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

    /**
     * Which picker is asking. CLOSED, and checked rather than trusted for the reason every other closed set
     * here is: a mode this build does not know would otherwise pick a default, and the menu would quietly
     * list the wrong things — the kind of wrong that is never reported as a bug.
     */
    private fun projectTreeMode(wire: String): ProjectTree.Mode? = when (wire) {
        "files" -> ProjectTree.Mode.FILES
        "directories" -> ProjectTree.Mode.DIRECTORIES
        else -> null
    }

    /** One folder's children, for the attach menu's in-place project browser. */
    private fun onTreeChildren(m: JcefBridge.Msg.TreeChildren) {
        val mode = projectTreeMode(m.mode) ?: return unknownTreeMode(m.mode)
        pushOffEdt("window.cc.treeChildren") {
            JcefTreeData.childrenJson(m.path, m.mode, ProjectTree.children(panel.project, m.path, mode))
        }
    }

    /** What marking that folder drags in — the paths themselves, since the count is the walk that found them. */
    private fun onTreeExpand(m: JcefBridge.Msg.TreeExpand) {
        val mode = projectTreeMode(m.mode) ?: return unknownTreeMode(m.mode)
        pushOffEdt("window.cc.treeExpansion") {
            JcefTreeData.expansionJson(m.path, m.mode, ProjectTree.expand(panel.project, m.path, mode))
        }
    }

    /**
     * Pin a whole batch the tree picked, named in ITS vocabulary: root-relative, forward-slashed.
     *
     * [ProjectTree.resolve] is the single crossing to a real file and therefore the only containment check —
     * the same canonicalize-and-prefix gate that confines what the binary may write. Anything that does not
     * name a file inside the project is simply absent from the result, including a value that arrived
     * absolute. Bounded by the tree's own ceiling, because the size of this list is decided by a browser.
     *
     * **A drop is LOGGED rather than passed over.** Every path in this message was drawn from a listing this
     * same gate produced, so one that no longer resolves means the page offered something the project does
     * not contain — and the visible symptom is a batch that pins fewer chips than the button promised, which
     * reads as the feature losing files. Not a balloon, though: it is a defect on our side, not something the
     * user did or can act on.
     *
     * Off the EDT because the gate canonicalizes, which is a filesystem call per path; only the pinning is
     * EDT work, and it is ONE operation there — see [AttachmentTray.addPaths].
     */
    private fun onAttachPaths(paths: List<String>) {
        if (paths.isEmpty()) return
        val root = panel.project.basePath
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val wanted = paths.take(ProjectTree.MAX_ENTRIES)
            val files = wanted.mapNotNull { ProjectTree.resolve(root, it)?.path }
            if (files.size != wanted.size) {
                val lost = wanted.size - files.size
                logger.warn("Claude Code: $lost of ${wanted.size} attached paths name nothing inside this project")
            }
            if (files.isEmpty()) return@executeOnPooledThread
            app.invokeLater({ tray.addPaths(files) }, ModalityState.any())
        }
    }

    private fun unknownTreeMode(wire: String) =
        logger.warn("The attach menu asked to browse the project in a mode this build does not have: $wire")

    /**
     * Answers a page question on a POOLED thread and pushes the reply back on the EDT.
     *
     * [ProjectTree]'s two entry points take a read lock and walk the VFS, which on the EDT is a frozen IDE —
     * the same shape [resolveLinksOffEdt] uses for the transcript's links. A failure is LOGGED rather than
     * swallowed: the page is waiting for this reply and shows a folder stuck on "Loading…" without it, so a
     * silent drop is a menu that appears to hang for no reason anybody can find afterwards.
     */
    private fun pushOffEdt(method: String, build: () -> JsonObject) {
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val payload = runCatching(build)
                .onFailure { logger.warn("Claude Code: $method could not be answered", it) }
                .getOrNull() ?: return@executeOnPooledThread
            // The house idiom, guard included: a push that lands before the module registered would otherwise
            // throw inside a page where nothing surfaces a throw.
            app.invokeLater({ panel.host.exec("$method && $method($payload)") }, ModalityState.any())
        }
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

        is JcefBridge.Msg.SetWorkloadWindow -> onSetWorkloadWindow(m.minutes)

        is JcefBridge.Msg.GitAction -> onGitAction(m)

        // The three buttons on the composer's action row that need the host (app-composer-actions.js). They
        // were Swing `AnAction`s in the tool window's title bar and reach exactly the same code; *Stop* and
        // *Log out* are not here because they already had a message ([Interrupt], [Logout]) and reuse it.
        //
        // None of them is about THIS session, which is why none of them goes through `session`: a new tab, the
        // editor's diff tabs and the Git chat are all owned by the tool window, and the only door onto it from
        // inside a chat is [ClaudeToolWindowFactory]'s companion.
        JcefBridge.Msg.NewChat -> ClaudeToolWindowFactory.newChat(panel.project)

        // The bin in the actions row. It resolves the tab from THIS panel's session rather than from an id the
        // page sent, because the page cannot be wrong about which chat it is and an id can: the same close
        // travels through `closeById` when the tab row asks, and through here when the chat asks about itself.
        JcefBridge.Msg.CloseThisChat -> withStrip("close this chat") { strip ->
            strip.tabFor(session)?.let { strip.close(it) }
        }

        JcefBridge.Msg.OpenGitView -> ClaudeToolWindowFactory.showGitView(panel.project)

        // …and its sibling: the same tab, but landing on the CONVERSATION rather than on the repository view.

        // Everything the tab bar and the two onboarding cards send lives in its own collaborator — see
        // [onNavigation] and OnboardingController. Both report whether the message was theirs, and every
        // remaining SessionControl IS handled above, so falling through here means a new message was added
        // without a handler: surface it instead of ignoring it.
        else -> {
            if (!onNavigation(m) && !panel.onboarding.handle(m)) {
                logger.warn("unhandled session-control message: $m")
            }
            Unit
        }
    }

    /**
     * Going somewhere: the tab bar's own clicks, and the cards that send you to an agent or a task.
     *
     * Split out of [onSessionControl] for complexity only, and split HERE because these are the arms that
     * reach the tab strip rather than the session. Returns false for a message that is not one of them, which
     * is how the caller can tell "not mine" from "handled".
     */
    private fun onNavigation(m: JcefBridge.Msg.SessionControl): Boolean {
        when (m) {
            // The transcript card (and the dashboard lists) asking to go to an agent's tab. Reopens it when the
            // user had closed it: closing hides a view, it never removes the agent or its transcript.
            //
            // With nothing to resolve it means the CHAT's own transcript — a background task the binary never
            // attributed to an agent still ran somewhere, and that somewhere is this chat.
            is JcefBridge.Msg.RevealAgent -> panel.agentTabs.revealElsewhere(m.chatId) { it.agentTabs.revealFromHost(m) }

            is JcefBridge.Msg.RevealBackgroundTask ->
                panel.agentTabs.revealElsewhere(m.chatId) { it.transcript.showBackgroundTask(m.taskId) }

            // The "Chat" button. It used to only close the dashboard, so pressed while an agent's or a task's
            // transcript was on screen it did nothing — and that is precisely the state a user wants out of.
            is JcefBridge.Msg.ShowChatTranscript -> panel.transcript.showTranscript(null)

            // The tab bar lives in the page, so its clicks arrive here like any other web→host message. Both
            // of these SAY SO when the strip cannot be resolved, rather than being an elegant no-op: a `?.` in
            // front of the only thing a button does is a button that appears broken and leaves no trace, and
            // the close button spent a day in exactly that state.
            is JcefBridge.Msg.SelectChat -> withStrip("select chat ${m.chatId}") { it.selectById(m.chatId) }

            is JcefBridge.Msg.CloseChat -> withStrip("close chat ${m.chatId}") { it.closeById(m.chatId) }

            // No id means the chat's own transcript: that is how the breadcrumb's first segment goes back, and
            // `Shown.Agent("")` would be a transcript for an agent that does not exist — an empty page.
            is JcefBridge.Msg.SelectAgent -> panel.transcript.showTranscript(m.agentId.ifBlank { null })

            is JcefBridge.Msg.CloseAgent -> panel.agentTabs.closeAgent(m.agentId)

            else -> return false
        }
        return true
    }

    private fun onLifecycle(m: JcefBridge.Msg.Lifecycle) = when (m) {
        JcefBridge.Msg.Ready -> {
            panel.host.markWebReady() // the web app is alive — cancel the first-open self-heal watchdog
            panel.pushTheme()
            panel.pushSettingsMenu()
            panel.pushMetaState()
            panel.pushPermissions()
            tray.push()
            panel.pushSession()
            feed.requestMcp()
            feed.requestVersion()
            // The tab bar, like everything above it: this message means a page that knows NOTHING, whether it
            // is the first load, a reload, or the next rung of the delivery ladder after one that failed.
            // [ChatAgentTabs.render] is the only thing that ever emits `window.cc.tabs`, and the rest of its
            // callers are events — a chat added, an agent scanned, a tab revealed or closed — so a page that
            // came up between two of them had an empty chat list. The page then hides `#tabsbar` entirely,
            // taking the dashboard's own view buttons with it (they are appended into that node), which is
            // how a recovered page came back with neither tabs nor Workloads/Git/Plan.
            panel.agentTabs.render()
            // The Git view's first read. It has to happen once the page exists rather than in the panel's
            // constructor: collecting spawns `git log`, so the answer arrives asynchronously, and a push into a
            // browser that has not loaded yet is discarded.
            panel.pushGit()
            panel.transcript.fullResync()
        }

        // INFO, not WARN. It fires once per chat tab opened, so WARN would put a warning in idea.log for a
        // healthy session — and a log that cries wolf is one nobody reads when it finally matters. INFO is the
        // IDE's default level, so it is still there when someone needs to read it back.
        // WARN for a throw, INFO for the environment report. The report fires once per chat tab opened, so
        // warning on it would put a warning in idea.log for a healthy session — and a log that cries wolf is
        // one nobody reads when it finally matters. An uncaught error is the opposite: it is the only trace
        // there is, because the CEF console goes nowhere and a broken module fails silently on screen.
        is JcefBridge.Msg.Diagnostics ->
            if (m.report.startsWith("uncaught ")) {
                logger.warn("Claude Code chat page: ${m.report}")
            } else {
                logger.info("JCEF diagnostics: ${m.report}")
            }

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
