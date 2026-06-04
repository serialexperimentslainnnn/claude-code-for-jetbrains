package dev.lain.claudejb.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.scale.JBUIScale
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.lain.claudejb.context.Attachment
import dev.lain.claudejb.context.AttachmentEncoder
import dev.lain.claudejb.context.ClipboardImageReader
import dev.lain.claudejb.context.EditorContextProvider
import dev.lain.claudejb.context.FilePickerHelper
import dev.lain.claudejb.diff.DiffPresenter
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.SessionListener
import dev.lain.claudejb.settings.ClaudeSettings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.BasicStroke
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.datatransfer.DataFlavor
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.KeyStroke
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.TransferHandler

/**
 * The GUI for one chat tab: a claude.ai-style dark transcript ([TranscriptView]) above a card composer.
 * Permission requests appear as non-modal Accept/Reject cards in a tray just above the composer (file
 * edits also open an in-editor diff), so the agent never blocks the UI with a dialog.
 */
class ChatPanel(private val project: Project, val session: ClaudeSession) :
    JBPanel<ChatPanel>(BorderLayout()), Disposable, SessionListener {

    private val transcript = TranscriptView()

    private val input = JBTextArea(2, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Ask Claude, or type / for commands"
        border = JBUI.Borders.empty(8)
        font = ChatTheme.promptFont
        background = ChatTheme.CARD_BG
        foreground = ChatTheme.TEXT
        caretColor = ChatTheme.TEXT
        isOpaque = true
    }

    private val permissionTray = PermissionTrayPanel(
        onAccept = { id, override -> session.resolvePermission(id, allow = true, overrideInput = override) },
        onReject = { id -> session.resolvePermission(id, allow = false) },
        // Reuse the host's diff lifecycle: focus the already-open tab, else open it and remember it.
        onViewDiff = { id ->
            session.pendingPermissions().firstOrNull { it.requestId == id }?.let { request ->
                val file = diffFiles[id]
                if (file != null) DiffPresenter.revealDiff(project, file)
                else DiffPresenter.openDiff(project, request.toolName, request.input)
                    ?.let { diffFiles[id] = it }
            }
        },
        // Original behavior: remember the tool AND approve the request that surfaced the button.
        onAlwaysAllow = { tool ->
            ClaudeSettings.getInstance(project).rememberToolAlwaysAllow(tool)
            session.pendingPermissions().firstOrNull { it.toolName == tool }
                ?.let { session.resolvePermission(it.requestId, allow = true) }
        },
        onAnswer = { id, answers -> session.resolveQuestion(id, answers) },
    )

    private val queueStrip = QueueStripPanel { session.removeQueued(it) }

    // Pending attachments pinned to the next turn (files / selections / pasted-or-dropped images).
    // Clicking a chip body opens that attachment in the editor (project-confined).
    private val attachmentStrip = AttachmentStripPanel(onOpen = ::openAttachment)

    // Live subagent (Task tool) strip: one card per in-flight subagent, with a Stop button per task.
    private val subagentTasks = SubagentTasksPanel { taskId -> session.stopTask(taskId) }

    // Graphical session-consumption readout (context window + honest output tokens + unified quota/reset bar),
    // replacing the old three loose quota labels and the inline "— N tokens" suffix.
    private val usage = SessionUsagePanel()

    // option chips under the prompt — reflect the live session and open their popups on click. Each is a flat
    // capsule (PillChip) carrying a category glyph, so the label can shrink to just the live value (set in
    // refreshState) and the pill lights up with a coral glow on hover.
    private val modelChip = PillChip { it.popup(OptionMenus.modelGroup(session)) }.apply { icon = ChatTheme.iconChipModel }
    private val modeChip = PillChip { it.popup(OptionMenus.permissionModeGroup(session)) }.apply { icon = ChatTheme.iconChipMode }
    private val effortChip = PillChip { it.popup(OptionMenus.effortGroup(session)) }.apply { icon = ChatTheme.iconChipEffort }
    private val thinkingChip = PillChip { it.popup(OptionMenus.thinkingGroup(session)) }.apply { icon = ChatTheme.iconChipThinking }

    private val sendButton = SendIconButton { sendOrStop() }

    // Follow-output toggle: when on (default) the transcript force-follows the stream to the bottom even if you
    // scroll up; off lets you read history mid-stream (it still follows naturally while you're at the bottom).
    private var followingOutput = true
    private val followButton = chip { toggleFollowOutput() }.apply {
        icon = ChatTheme.iconFollowOn
        toolTipText = "Following output — click to stop auto-scrolling so you can read while Claude streams"
    }

    // 🌈 Vibe Mode: a gag toggle that animates the coral accent through the rainbow. The animation is driven by a
    // SINGLE shared timer in ChatTheme (see registerVibe); this panel just contributes a repaint hook and syncs its
    // own toggle glyph / tool-window icon / avatar layout when the global state flips.
    private val vibeButton = chip { toggleVibeMode() }.apply {
        icon = ChatTheme.iconVibe
        toolTipText = "Vibe Mode ✨ — neon & rainbows"
    }
    /** Last Vibe-Mode state this panel applied to its own UI (so [syncVibe] re-syncs only on a change). */
    private var vibeApplied = false
    /** Repaint hook registered with ChatTheme's shared vibe timer: repaint each frame + re-sync UI on a flip. */
    private val vibeRepaint: () -> Unit = { repaint(); syncVibe() }

    // Discoverable attach affordance: opens a menu to choose WHAT to attach (current file, selection, an
    // open file, a file from disk). Drag&drop / paste of images also work — see tooltip.
    private val attachButton = chip { it.popup(attachmentMenu()) }.apply {
        icon = ChatTheme.iconAttach
        toolTipText = "Attach context (file, selection, image) — you can also drag & drop or paste images"
    }

    // Native IDE "working" indicator: the platform's AsyncProcessIcon (the same spinner used across the IDE)
    // next to a rotating gerund — instead of hand-painted braille frames.
    private val thinkingIcon = AsyncProcessIcon("claude-thinking")
    private val statusLabel = JBLabel().apply {
        foreground = ChatTheme.TEXT_DIM
        font = ChatTheme.small
    }
    private val statusRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0)).apply {
        isOpaque = false
        border = JBUI.Borders.emptyBottom(2)
        add(thinkingIcon)
        add(statusLabel)
        isVisible = false
    }

    private val thinkingWords = listOf(
        "Pondering", "Cogitating", "Conjuring", "Musing", "Ruminating", "Brewing", "Synthesizing",
        "Percolating", "Noodling", "Marinating", "Computing", "Forging", "Distilling", "Scheming",
    )
    private var thinkingWord = thinkingWords.random()
    // Rotate the gerund periodically for personality; the motion itself is the native AsyncProcessIcon.
    private val wordTimer = javax.swing.Timer(WORD_CHANGE_MS) {
        thinkingWord = thinkingWords.random()
        updateStatus()
    }
    /** Request ids whose diff we've already triggered, so a tray rebuild doesn't reopen it every tick. */
    private val diffOpened = HashSet<String>()

    /** Open diff tabs by request id, so a resolved request's diff can be focused ("View diff") or closed. */
    private val diffFiles = HashMap<String, VirtualFile>()

    /** True while the prompt input holds focus — drives the composer card's coral focus ring. */
    private var promptFocused = false

    init {
        background = ChatTheme.BG
        add(transcript, BorderLayout.CENTER)
        add(buildComposer(), BorderLayout.SOUTH)

        installInputKeys()
        installPaste()
        installShortcuts()
        installImageDropPaste()

        // "View diff" button on each reviewable Edit/Write/MultiEdit row → reopen the persisted old↔new diff
        // (snapshot kept by tool_use id). Set BEFORE replaying existing entries so restored rows get it too.
        transcript.onViewDiff = { id ->
            session.editSnapshot(id)?.let { snap ->
                DiffPresenter.openDiff(project, snap.toolName, snap.input, snap.beforeText)
            }
        }
        // "Revert" button on each reviewable row → roll the file back to its captured pre-write snapshot (the
        // session reseeds the binary's read-state so its next Edit re-validates). EDT-confined (this callback fires
        // on the EDT). Set before replaying entries so restored rows get it too.
        transcript.onRevert = { id ->
            session.editSnapshot(id)?.let { snap -> session.revertEdit(snap) }
        }
        session.transcript.addListener(transcript)
        session.addListener(this)
        session.transcript.entries.forEachIndexed { index, entry -> transcript.onAdded(entry, index) }
        usage.bind(session)
        refreshState()
        rebuildPermissionTray()
        // The recurring poll is now session-scoped (one timer per ClaudeSession, shared by every observing
        // ChatPanel) and delivers results via onStateChanged → refreshState. refreshState() above already rendered
        // whatever the session has cached, so we only kick one immediate fetch here so a freshly-opened tab isn't
        // blank until the next tick.
        session.requestSessionCost { feedSessionCost(it) }
        session.requestContextUsage { cu -> usage.setContextUsage(cu) }
        // Join ChatTheme's shared Vibe-Mode animation (single timer across all tabs); sync now in case it's already
        // on (e.g. a second tab opened while another is vibing).
        ChatTheme.registerVibe(vibeRepaint)
        syncVibe()
    }

    /** Decode the authoritative `apiUsage` block from a `get_session_cost` response and feed it to the usage panel. */
    private fun feedSessionCost(json: kotlinx.serialization.json.JsonObject?) {
        val au = (json?.get("apiUsage") as? kotlinx.serialization.json.JsonObject)?.let {
            runCatching {
                dev.lain.claudejb.protocol.ClaudeJson.decodeFromJsonElement(
                    dev.lain.claudejb.protocol.SessionCostUsage.serializer(), it,
                )
            }.getOrNull()
        }
        usage.setApiUsage(au)
    }

    /** Card composer: trays (permissions, queue) on top, two-tone input card (gray input / dark options). */
    private fun buildComposer(): JComponent {
        val inputScroll = JBScrollPane(input).apply {
            preferredSize = Dimension(0, JBUIScale.scale(60))
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        // Two explicit rows. Row 1: the model/mode/effort/thinking pills, centred. Row 2: the vibe/follow/attach
        // toggles centred, with the Play/Stop button pinned to the right.
        val row1 = JPanel(FlowLayout(FlowLayout.CENTER, JBUIScale.scale(4), 0)).apply {
            isOpaque = false
            add(modelChip); add(modeChip); add(effortChip); add(thinkingChip)
        }
        val row2 = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JPanel(FlowLayout(FlowLayout.CENTER, JBUIScale.scale(4), 0)).apply {
                isOpaque = false
                add(vibeButton); add(followButton); add(attachButton)
            }, BorderLayout.CENTER)
            add(sendButton, BorderLayout.EAST)
        }
        val optionsBar = JPanel(VerticalLayout(JBUIScale.scale(2))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(3, 8, 3, 6)
            add(row1)
            add(row2)
        }

        val inputWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 10, 6, 10)
            add(inputScroll, BorderLayout.CENTER)
        }

        // Two-tone rounded card: editor-bg for the input zone, near-black for the options zone.
        val card = object : JPanel(BorderLayout()) {
            init { isOpaque = false }
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val arc = JBUIScale.scale(14)
                    val w = width; val h = height
                    val splitY = optionsBar.y.takeIf { optionsBar.height > 0 }
                        ?: (h - optionsBar.preferredSize.height)
                    val rr = RoundRectangle2D.Float(0f, 0f, w.toFloat(), h.toFloat(), arc.toFloat(), arc.toFloat())
                    val savedClip = g2.clip
                    g2.clip(rr)
                    g2.color = ChatTheme.CARD_BG
                    g2.fillRect(0, 0, w, splitY)
                    g2.color = JBColor(Color(0xDDDDDD), Color(0x1A1A1A))
                    g2.fillRect(0, splitY, w, h - splitY)
                    g2.clip = savedClip
                    if (ChatTheme.vibeMode) {
                        // 🌈 A two-stop rainbow gradient border that animates with the global hue.
                        g2.stroke = BasicStroke(JBUIScale.scale(2f))
                        g2.paint = java.awt.GradientPaint(
                            0f, 0f, ChatTheme.vibeColorAt(0f),
                            w.toFloat(), h.toFloat(), ChatTheme.vibeColorAt(0.5f),
                        )
                        g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc)
                    } else if (promptFocused) {
                        // Focus ring: a faint coral halo + a crisp coral outline, so the composer "lights up"
                        // when you're typing — the product accent doubling as a focus affordance.
                        val a = ChatTheme.ACCENT
                        g2.color = Color(a.red, a.green, a.blue, 55)
                        g2.stroke = BasicStroke(JBUIScale.scale(3f))
                        g2.drawRoundRect(1, 1, w - 3, h - 3, arc, arc)
                        g2.color = a
                        g2.stroke = BasicStroke(JBUIScale.scale(1.4f))
                        g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc)
                    } else {
                        g2.color = ChatTheme.BORDER
                        g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc)
                    }
                } finally {
                    g2.dispose()
                }
            }
        }
        card.add(inputWrapper, BorderLayout.CENTER)
        card.add(optionsBar, BorderLayout.SOUTH)
        // Light the focus ring as the prompt gains/loses focus.
        input.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent) { promptFocused = true; card.repaint() }
            override fun focusLost(e: java.awt.event.FocusEvent) { promptFocused = false; card.repaint() }
        })

        return JPanel(VerticalLayout(0)).apply {
            isOpaque = true
            background = ChatTheme.BG
            border = JBUI.Borders.empty(2, 18, 10, 18)
            add(usage)
            add(statusRow)
            add(permissionTray)
            add(subagentTasks)
            add(queueStrip)
            add(attachmentStrip)
            add(card)
        }
    }

    /** Ctrl+O toggles the visibility of all reasoning ("Thought process") blocks, like the CLI. */
    private fun installShortcuts() {
        val ctrlO = KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK)
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(ctrlO, "toggleReasoning")
        actionMap.put("toggleReasoning", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = transcript.toggleReasoning()
        })
    }

    /**
     * Lets the user drag&drop image files onto the composer, or paste an image from the clipboard, to pin them as
     * [Attachment.Image] chips. A custom [TransferHandler] tries the image flavor first (clipboard paste / direct
     * image drag), then a file list (dropped files). The transferable is read on the EDT (cheap), but the actual
     * file read + base64 encode happen on a pooled thread (they can be multi-MB) before hopping back to pin the chip
     * on the EDT. Non-image transfers fall through to the text area's default paste so typing is unaffected.
     */
    private fun installImageDropPaste() {
        // Capture the text area's existing handler in a LOCAL *before* assigning ours, so we can delegate to it
        // (a `private val default = input.transferHandler` field would read null — it's evaluated during this very
        // assignment — which silently broke text paste and drop delegation).
        val previous = input.transferHandler
        input.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean =
                support.isDataFlavorSupported(DataFlavor.imageFlavor) ||
                    support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
                    // Linux (Wayland over XWayland / X11): a clipboard or dragged image arrives as a raw image/* flavor, not imageFlavor.
                    support.dataFlavors.any { it.primaryType == "image" } ||
                    previous?.canImport(support) == true

            override fun importData(support: TransferSupport): Boolean {
                if (importImages(support.transferable)) return true
                return previous?.importData(support) ?: false
            }

            // Paste (Ctrl/Cmd+V) routes here too → handle image clipboard, else fall back to the default text paste.
            override fun importData(comp: JComponent, t: java.awt.datatransfer.Transferable): Boolean {
                if (importImages(t)) return true
                return previous?.importData(comp, t) ?: false
            }

            override fun getSourceActions(comp: JComponent?): Int = previous?.getSourceActions(comp) ?: NONE
        }
    }

    /**
     * Intercepts paste on the composer using the IDE-native action system: a [DumbAwareAction] bound to the platform
     * `$Paste` action's own [ShortcutSet] (so it honours the user's keymap and is correct on every OS — Cmd on macOS,
     * Ctrl elsewhere — with no hand-rolled key masks), registered on [input] and scoped to this panel's lifetime.
     * This is needed because on Linux (Wayland over XWayland / X11) the text area's default keyboard paste doesn't
     * route a raw `image/…` clipboard through the [TransferHandler], so a pasted screenshot was dropped. Clipboard
     * access goes through the IDE's cross-platform [CopyPasteManager]: if it carries an image
     * ([ClipboardImageReader.hasImage]) we attach it, otherwise we fall back to the normal text paste (which re-enters
     * the transfer handler, where [importImages] returns false for text and the default paste runs).
     */
    private fun installPaste() {
        val pasteShortcuts = ActionManager.getInstance().getAction(IdeActions.ACTION_PASTE).shortcutSet
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                val handledImage = runCatching {
                    val contents = CopyPasteManager.getInstance().contents
                    contents != null && ClipboardImageReader.hasImage(contents) && importImages(contents)
                }.getOrDefault(false)
                if (!handledImage) input.paste()
            }
        }.registerCustomShortcutSet(pasteShortcuts, input, this)
    }

    /**
     * Reads any image content from [t] (rendered image or image files) into attachment chips. The transferable is
     * read here (EDT), but the potentially-large file read + base64 encode are dispatched to a pooled thread, hopping
     * back to the EDT to pin each chip. Returns true if [t] carried image content this handler claimed.
     */
    private fun importImages(t: java.awt.datatransfer.Transferable): Boolean {
        if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            @Suppress("UNCHECKED_CAST")
            val files = runCatching { t.getTransferData(DataFlavor.javaFileListFlavor) as List<java.io.File> }
                .getOrNull().orEmpty()
            if (files.isNotEmpty()) {
                com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                    for (f in files) {
                        // Guard on size BEFORE reading, so a huge file is never slurped into memory.
                        if (f.isFile && f.length() in 1..AttachmentEncoder.MAX_IMAGE_BYTES.toLong()) {
                            val att = runCatching { AttachmentEncoder.fromBytes(f.name, f.readBytes()) }.getOrNull()
                            if (att != null) SwingUtilities.invokeLater { addAttachment(att) }
                        }
                    }
                }
                return true
            }
        }
        // Raw image bytes — clipboard paste / image drag, including the Linux (Wayland/X11) `image/*` InputStream-or-byte[]
        // case that DataFlavor.imageFlavor misses. Delegated to the pure ClipboardImageReader; encoding + 8MB cap
        // are enforced by AttachmentEncoder on a pooled thread (the payload can be multi-MB).
        ClipboardImageReader.readImageBytes(t)?.let { image ->
            com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                val att = runCatching {
                    AttachmentEncoder.fromBytes(image.suggestedName ?: "pasted-image.png", image.bytes)
                }.getOrNull()
                if (att != null) SwingUtilities.invokeLater { addAttachment(att) }
            }
            return true
        }
        // Rendered AWT image fallback (apps that expose only DataFlavor.imageFlavor, e.g. some macOS/Windows sources).
        if (t.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            val img = runCatching { t.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image }.getOrNull()
            if (img != null) {
                com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                    val att = runCatching { encodeRenderedImage(img) }.getOrNull()
                    if (att != null) SwingUtilities.invokeLater { addAttachment(att) }
                }
                return true
            }
        }
        return false
    }

    /** PNG-encodes a clipboard [java.awt.Image] into an [Attachment.Image], or null if it can't be rendered. */
    private fun encodeRenderedImage(img: java.awt.Image?): Attachment? {
        img ?: return null
        val w = img.getWidth(null); val h = img.getHeight(null)
        if (w <= 0 || h <= 0) return null
        val buffered = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        buffered.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
        val baos = java.io.ByteArrayOutputStream()
        return if (javax.imageio.ImageIO.write(buffered, "png", baos))
            AttachmentEncoder.fromBytes("pasted-image.png", baos.toByteArray())
        else null
    }

    private fun installInputKeys() {
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when {
                    e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown -> {
                        e.consume()
                        sendInput()
                    }
                    // Shift+Enter has no default binding in JTextArea, so insert the newline ourselves.
                    e.keyCode == KeyEvent.VK_ENTER && e.isShiftDown -> {
                        e.consume()
                        input.replaceSelection("\n")
                    }
                    e.keyCode == KeyEvent.VK_ESCAPE && session.turnActive -> {
                        e.consume()
                        session.interrupt()
                    }
                    e.keyCode == KeyEvent.VK_TAB && e.isShiftDown -> {
                        e.consume()
                        session.cyclePermissionMode()
                    }
                }
            }

            override fun keyReleased(e: KeyEvent) {
                if (input.text == "/") showCommandPalette()
            }
        })
    }

    private fun sendOrStop() {
        if (session.turnActive) session.interrupt() else sendInput()
    }

    /** Flips the transcript's force-follow: on → always stick to the streaming bottom; off → let you read history
     *  mid-stream (still follows naturally while you're at the bottom). Updates the toggle's glyph + tooltip. */
    private fun toggleFollowOutput() {
        followingOutput = !followingOutput
        transcript.followOutput = followingOutput
        followButton.icon = if (followingOutput) ChatTheme.iconFollowOn else ChatTheme.iconFollow
        followButton.toolTipText = if (followingOutput)
            "Following output — click to stop auto-scrolling so you can read while Claude streams"
        else
            "Not following — click to keep the latest output in view as Claude streams"
    }

    /** 🌈 Flips Vibe Mode: starts/stops the rainbow-hue animation timer and swaps the toggle glyph. The accent
     *  colour is global ([ChatTheme.vibeMode]) so every painted control shimmers while it's on. */
    /** Flips Vibe Mode **globally** via ChatTheme's coordinator; every registered panel (this one and any other open
     *  tab) re-syncs and animates from the single shared timer. */
    private fun toggleVibeMode() = ChatTheme.setVibeMode(!ChatTheme.vibeMode)

    /** Brings this panel's vibe-dependent chrome in line with the global state when it flips: the toggle glyph, the
     *  tool-window stripe icon (neon Nyan while vibing), and the transcript relayout for the swapped avatar size. */
    private fun syncVibe() {
        val on = ChatTheme.vibeMode
        if (on == vibeApplied) return
        vibeApplied = on
        vibeButton.icon = if (on) ChatTheme.iconVibeOn else ChatTheme.iconVibe
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Claude Code")
            ?.setIcon(if (on) ChatTheme.iconVibeOn else ChatTheme.iconToolWindow)
        transcript.relayout()
    }

    private fun sendInput() {
        val text = input.text
        val attachments = attachmentStrip.items()
        if (text.isBlank() && attachments.isEmpty()) return
        input.text = ""
        attachmentStrip.clear()
        // /login can't run in the TTY-less stream-json session (the binary answers "not available on this
        // environment"). Intercept it client-side and run `claude auth login` natively under a PTY.
        if (attachments.isEmpty() && text.trim().equals("/login", ignoreCase = true)) {
            session.startLogin()
            return
        }
        // /btw <question>: client-side command (the SDK doesn't expose it) — send a side question mid-turn.
        // (Only when there are no attachments — a side question is plain text by design.)
        val btw = Regex("^/btw\\b\\s*", RegexOption.IGNORE_CASE)
        if (attachments.isEmpty() && btw.containsMatchIn(text)) {
            val question = text.replaceFirst(btw, "").trim()
            if (question.isNotEmpty()) {
                session.sendSideQuestion(question)
                return
            }
        }
        session.send(text, attachments)
    }

    /** Pins an attachment (file / selection / image) to the next turn — the editor actions and image drops call this. */
    fun addAttachment(attachment: Attachment) {
        attachmentStrip.pin(attachment)
        input.requestFocusInWindow()
    }

    /**
     * The 📎 attach menu: pick WHAT to attach instead of always grabbing the open file. Items enable themselves to
     * what's available — current file, current selection, an image from the clipboard — so it reads like a proper
     * selector (à la AI Assistant) rather than a one-shot button.
     */
    private fun attachmentMenu(): DefaultActionGroup = DefaultActionGroup().apply {
        add(menuItem("Current file") { mentionCurrentFile() })
        add(menuItem("Current selection") {
            EditorContextProvider.selectionAsAttachment(project)?.let { addAttachment(it) }
        })
        add(menuItem("Image from clipboard") { pasteImageFromClipboard() })
        addSeparator()
        add(menuItem("Add files…") { FilePickerHelper.chooseFiles(project).forEach { addFileRef(it) } })
        add(menuItem("Add directory…") { FilePickerHelper.chooseDirectory(project)?.let { addFileRef(it) } })
        add(openFilesSubmenu())
        add(recentFilesSubmenu())
    }

    /** Submenu pinning any currently-open editor file as an `@`-context chip (root-relative label). */
    private fun openFilesSubmenu(): DefaultActionGroup =
        DefaultActionGroup("Add open files…", true).apply {
            val open = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFiles.toList()
            if (open.isEmpty()) add(disabledItem("No open files"))
            else open.forEach { vf -> add(menuItem(FilePickerHelper.displayName(project, vf.path)) { addFileRef(vf.path) }) }
        }

    /** Submenu pinning a recently-opened file (newest first) as an `@`-context chip. */
    private fun recentFilesSubmenu(): DefaultActionGroup =
        DefaultActionGroup("Add recent files…", true).apply {
            val recent = FilePickerHelper.recentFiles(project, RECENT_FILES_LIMIT)
            if (recent.isEmpty()) add(disabledItem("No recent files"))
            else recent.forEach { path -> add(menuItem(FilePickerHelper.displayName(project, path)) { addFileRef(path) }) }
        }

    /** Pins a file path as a [Attachment.FileRef] with a root-relative display name (avoids basename-dedupe clashes). */
    private fun addFileRef(path: String) {
        addAttachment(Attachment.FileRef(path, FilePickerHelper.displayName(project, path)))
    }

    private fun menuItem(text: String, run: () -> Unit) = object : com.intellij.openapi.actionSystem.AnAction(text) {
        override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) = run()
        override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
    }

    /** A non-actionable, greyed placeholder item shown when a submenu has nothing to list. */
    private fun disabledItem(text: String) = object : com.intellij.openapi.actionSystem.AnAction(text) {
        override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {}
        override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) { e.presentation.isEnabled = false }
        override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
    }

    /** Pulls an image off the clipboard (via the IDE's cross-platform [CopyPasteManager]) and pins it as an attachment. */
    private fun pasteImageFromClipboard() {
        val contents = runCatching { CopyPasteManager.getInstance().contents }.getOrNull() ?: return
        importImages(contents)
    }

    /** Pins the current editor file as a [Attachment.FileRef] chip (root-relative label), like the CLI's @ mentions. */
    fun mentionCurrentFile() {
        val path = EditorContextProvider.currentFilePath(project) ?: return
        addFileRef(path)
    }

    /** Opens an attachment in the editor when its chip is clicked: a file at its top, a selection at its start line. */
    private fun openAttachment(a: Attachment) {
        when (a) {
            is Attachment.FileRef -> openPath(a.path, null)
            is Attachment.Selection -> openPath(a.path, a.startLine)
            is Attachment.Image -> { /* image preview is a follow-up; no editor target */ }
        }
    }

    /** Navigates to [path] (project-confined) at the given 1-based [line], or its top when null. */
    private fun openPath(path: String, line: Int?) {
        if (!DiffPresenter.isWithinRoot(path, project.basePath)) return
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path) ?: return
        com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vf, ((line ?: 1) - 1).coerceAtLeast(0), 0)
            .navigate(true)
    }

    fun showCommandPalette() = CommandPalette.show(project, input, session) { command ->
        input.text = "/${command.name} "
        input.caretPosition = input.text.length
        input.requestFocusInWindow()
    }

    fun focusInput() = input.requestFocusInWindow()

    // -----------------------------------------------------------------------
    // SessionListener  (EDT)
    // -----------------------------------------------------------------------

    override fun onStateChanged() = refreshState()
    override fun onMetadataChanged() = refreshState()
    override fun onPermissionsChanged() = rebuildPermissionTray()

    private fun refreshState() {
        // The session-scoped quota poll caches its latest results and fires onStateChanged; render them here so
        // every panel observing the session reflects the single shared poll (no per-panel timer).
        feedSessionCost(session.lastSessionCost)
        usage.setContextUsage(session.lastContextUsage)
        usage.refresh()
        subagentTasks.update(session.subagentTasks.values.toList())
        queueStrip.update(session.queuedPrompts())
        // The category is conveyed by each chip's glyph, so the label carries only the live value (full name in the
        // tooltip). Pre-init (or a fixture that doesn't report one) leaves model null; default resolves to Opus 4.8.
        val modelName = session.model?.let { shortModel(it) } ?: "Default · Opus 4.8"
        modelChip.text = "$modelName  ▾"; modelChip.toolTipText = "Model — $modelName"
        val modeName = dev.lain.claudejb.session.PermissionMode.labelFor(session.permissionMode)
        modeChip.text = "$modeName  ▾"; modeChip.toolTipText = "Permission mode — $modeName"
        val effortName = session.effort ?: "default"
        effortChip.text = "$effortName  ▾"; effortChip.toolTipText = "Reasoning effort — $effortName"
        val thinkingOn = session.thinkingTokens != null
        thinkingChip.text = (if (thinkingOn) "on" else "off") + "  ▾"
        thinkingChip.toolTipText = "Extended thinking — " + (if (thinkingOn) "on" else "off")
        thinkingChip.icon = if (thinkingOn) ChatTheme.iconChipThinkingOn else ChatTheme.iconChipThinking
        sendButton.isStopMode = session.turnActive
        if (session.turnActive) {
            statusRow.isVisible = true
            thinkingIcon.resume()
            if (!wordTimer.isRunning) wordTimer.start()
        } else {
            wordTimer.stop()
            thinkingIcon.suspend()
            statusRow.isVisible = false
        }
        updateStatus()
    }

    /**
     * Renders the animated "thinking" word while a turn is active. The token/quota figures now live in the
     * graphical [SessionUsagePanel] above, so this line is just the spinner + interrupt hint.
     */
    private fun updateStatus() {
        if (session.turnActive) {
            statusLabel.text = "$thinkingWord…   (Esc to interrupt)"
        } else {
            statusLabel.text = ""
        }
    }

    private fun shortModel(value: String): String {
        // Prefer the binary-reported display name; else derive a friendly label from the id, joining the version
        // segments with dots so "claude-opus-4-8" → "Opus 4.8" (not "Opus 4 8").
        session.models.firstOrNull { it.value == value }?.displayName?.ifBlank { null }?.let { return it }
        val segs = value.removePrefix("claude-").split("-").filter { it.isNotEmpty() }
        val name = segs.filter { !it[0].isDigit() }.joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
        val version = segs.filter { it[0].isDigit() }.joinToString(".")
        return listOf(name, version).filter { it.isNotBlank() }.joinToString(" ").ifBlank { value }
    }

    // -----------------------------------------------------------------------
    // Permission tray  (EDT)
    // -----------------------------------------------------------------------

    /**
     * Repaints the tray ([PermissionTrayPanel.update]) and runs the diff lifecycle the panel deliberately does
     * not own: auto-open the in-editor diff the first time a reviewable request appears, and close the diff tab
     * of any request that is no longer pending (accepted/rejected/cleared).
     */
    private fun rebuildPermissionTray() {
        val pending = session.pendingPermissions()
        permissionTray.update(pending)
        pending.forEach { request ->
            // For a file edit, surface the diff in the editor the first time the card appears (deferred so it
            // doesn't reenter while the tray is still laying out / opening a file editor mid-revalidate).
            if (request.reviewable && diffOpened.add(request.requestId)) {
                SwingUtilities.invokeLater {
                    if (session.pendingPermissions().any { it.requestId == request.requestId }) {
                        DiffPresenter.openDiff(project, request.toolName, request.input)
                            ?.let { diffFiles[request.requestId] = it }
                    }
                }
            }
        }
        // A request no longer pending was accepted/rejected/cleared: forget it and close its diff tab.
        val live = pending.mapTo(HashSet()) { it.requestId }
        (diffOpened - live).forEach { id ->
            diffOpened.remove(id)
            diffFiles.remove(id)?.let { DiffPresenter.closeDiff(project, it) }
        }
    }

    // -----------------------------------------------------------------------
    // small dark controls
    // -----------------------------------------------------------------------

    private fun chip(onClick: (JComponent) -> Unit): JButton = JButton().apply {
        isFocusable = false
        isContentAreaFilled = false
        isBorderPainted = false
        isOpaque = false
        foreground = ChatTheme.TEXT_DIM
        font = JBFont.medium()
        iconTextGap = JBUIScale.scale(4)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        margin = JBUI.insets(2, 6)
        // Visible hover: the label brightens from dim → full text colour while the cursor is over the chip.
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent) { foreground = ChatTheme.TEXT }
            override fun mouseExited(e: java.awt.event.MouseEvent) { foreground = ChatTheme.TEXT_DIM }
        })
        addActionListener { onClick(this) }
    }

    /**
     * A flat **capsule** option chip: transparent at rest (just a hairline outline), it fills with a translucent
     * coral glow and a coral outline on hover — a sleeker, more futuristic take on the bottom options bar than a
     * boxed segmented control. Paints its own rounded background, then lets the button UI draw the icon + label on
     * top. Colour identity is the fixed coral [ChatTheme.ACCENT]; the resting outline follows the IDE theme.
     */
    private inner class PillChip(onClick: (JComponent) -> Unit) : JButton() {
        private var hovered = false

        init {
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            isOpaque = false
            isRolloverEnabled = false // we paint our own hover, so the LAF doesn't fight it
            foreground = ChatTheme.TEXT_DIM
            font = JBFont.medium()
            iconTextGap = JBUIScale.scale(5)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(JBUIScale.scale(4), JBUIScale.scale(11))
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseEntered(e: java.awt.event.MouseEvent) { hovered = true; foreground = ChatTheme.TEXT; repaint() }
                override fun mouseExited(e: java.awt.event.MouseEvent) { hovered = false; foreground = ChatTheme.TEXT_DIM; repaint() }
            })
            addActionListener { onClick(this) }
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = height // fully rounded capsule
                val a = ChatTheme.ACCENT
                when {
                    hovered || model.isPressed -> {
                        val fillA = if (model.isPressed) 56 else 34
                        g2.color = Color(a.red, a.green, a.blue, fillA)
                        g2.fillRoundRect(0, 0, width, height, arc, arc)
                        g2.color = Color(a.red, a.green, a.blue, 170)
                        g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                    }
                    // 🌈 Vibe Mode: the pill carries a permanent animated rainbow outline (+ faint fill).
                    ChatTheme.vibeMode -> {
                        g2.color = Color(a.red, a.green, a.blue, 26)
                        g2.fillRoundRect(0, 0, width, height, arc, arc)
                        g2.color = Color(a.red, a.green, a.blue, 200)
                        g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                    }
                    else -> {
                        // Resting: a faint themed hairline outline so the capsule shape reads even un-hovered.
                        g2.color = ChatTheme.BORDER
                        g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                    }
                }
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    private fun JComponent.popup(group: DefaultActionGroup) {
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            null, group, DataContext.EMPTY_CONTEXT,
            JBPopupFactory.ActionSelectionAid.MNEMONICS, true,
        )
        // The chips sit at the bottom of the tool window: anchor ABOVE the chip, not below
        // (showUnderneathOf would push it off the edge / into the bottom-left corner).
        popup.show(RelativePoint(this, java.awt.Point(0, -popup.content.preferredSize.height)))
    }

    override fun dispose() {
        wordTimer.stop()
        ChatTheme.unregisterVibe(vibeRepaint)
        thinkingIcon.suspend()
        thinkingIcon.dispose()
        // No per-panel quota timer to stop anymore — the poll is session-scoped and stops when the last
        // observer detaches (removeListener) / the session is disposed.
        session.transcript.removeListener(transcript)
        session.removeListener(this)
    }

    /** Transparent icon button: right-pointing triangle outline (send) or outlined square (stop). */
    private class SendIconButton(private val onClick: () -> Unit) : JButton() {
        var isStopMode: Boolean = false
            set(v) { field = v; repaint() }

        init {
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            val s = JBUIScale.scale(32)
            preferredSize = Dimension(s, s)
            minimumSize = Dimension(s, 0)
            maximumSize = Dimension(s, Int.MAX_VALUE)
            addActionListener { onClick() }
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                val w = width; val h = height

                // Neon outline glyph: a hollow triangle (send) / rounded square (stop), stroked in the product
                // accent — coral normally, the animated rainbow in Vibe Mode. A faint hover chip behind it.
                if (model.isRollover || model.isPressed) {
                    val alpha = if (model.isPressed) 45 else 24
                    g2.color = Color(255, 255, 255, alpha)
                    g2.fillRoundRect(2, 2, w - 4, h - 4, JBUIScale.scale(6), JBUIScale.scale(6))
                }

                val base = ChatTheme.ACCENT
                g2.color = if (isEnabled) base else Color(base.red, base.green, base.blue, 90)
                g2.stroke = BasicStroke(JBUIScale.scale(1.7f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

                if (isStopMode) {
                    val sq = JBUIScale.scale(11)
                    g2.drawRoundRect((w - sq) / 2, (h - sq) / 2, sq, sq, JBUIScale.scale(3), JBUIScale.scale(3))
                } else {
                    // Send triangle outline, optically centred (nudged right).
                    val cx = w / 2 + JBUIScale.scale(1)
                    val cy = h / 2
                    val r = JBUIScale.scale(7)
                    g2.drawPolygon(
                        intArrayOf(cx + r, cx - r, cx - r),
                        intArrayOf(cy,     cy - r, cy + r),
                        3,
                    )
                }
            } finally {
                g2.dispose()
            }
        }
    }

    companion object {
        /** Interval (ms) between thinking-word changes (the motion itself is the native AsyncProcessIcon). */
        private const val WORD_CHANGE_MS = 4_800

        /** How many recently-opened files the attach menu's "Add recent files…" submenu lists. */
        private const val RECENT_FILES_LIMIT = 15
    }
}
