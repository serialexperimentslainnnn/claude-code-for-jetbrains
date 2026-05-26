package dev.lain.claudejb.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.lain.claudejb.context.EditorContextProvider
import dev.lain.claudejb.diff.DiffPresenter
import dev.lain.claudejb.permission.PendingPermission
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.SessionListener
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.KeyStroke
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

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
        font = JBFont.label()
        background = ChatTheme.CARD_BG
        foreground = ChatTheme.TEXT
        caretColor = ChatTheme.TEXT
        isOpaque = true
    }

    private val permissionTray = JPanel(VerticalLayout(JBUI.scale(6))).apply {
        isOpaque = false
        border = JBUI.Borders.emptyBottom(6)
        isVisible = false
    }

    private val queueStrip = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.emptyBottom(4)
        isVisible = false
    }

    // Subscription quota bar shown above the prompt; fills with utilization% and turns amber/red on warning.
    private val quotaLabel = JBLabel().apply {
        font = ChatTheme.small
        foreground = ChatTheme.TEXT_DIM
        border = JBUI.Borders.emptyRight(8)
    }
    private val quotaBar = JProgressBar(0, 100).apply {
        isStringPainted = true
        font = ChatTheme.small
    }
    private val quotaPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.emptyBottom(6)
        add(quotaLabel, BorderLayout.WEST)
        add(quotaBar, BorderLayout.CENTER)
        isVisible = false
    }

    // option chips under the prompt — reflect the live session and open their popups on click
    private val modelChip = chip { it.popup(OptionMenus.modelGroup(session)) }
    private val modeChip = chip { it.popup(OptionMenus.permissionModeGroup(session)) }
    private val effortChip = chip { it.popup(OptionMenus.effortGroup(session)) }
    private val thinkingChip = chip { it.popup(OptionMenus.thinkingGroup(session)) }

    private val sendButton = RoundedActionButton("Send") { sendOrStop() }

    private val statusLabel = JBLabel().apply {
        foreground = ChatTheme.TEXT_DIM
        font = JBFont.medium()
    }

    /** Live output-token count shown at the right of the status bar while a turn runs. */
    private val tokensLabel = JBLabel().apply {
        foreground = ChatTheme.TEXT_DIM
        font = JBFont.medium()
        horizontalAlignment = SwingConstants.RIGHT
    }
    private val statusBar = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(statusLabel, BorderLayout.WEST)
        add(tokensLabel, BorderLayout.EAST)
    }

    // CLI-style "thinking" spinner: a rotating gerund + braille frames, animated only while a turn is active.
    private val thinkingWords = listOf(
        "Pondering", "Cogitating", "Conjuring", "Musing", "Ruminating", "Brewing", "Synthesizing",
        "Percolating", "Noodling", "Marinating", "Computing", "Forging", "Distilling", "Scheming",
    )
    private val spinnerFrames = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
    private var spinnerTick = 0
    private var thinkingWord = thinkingWords.random()
    private val spinnerTimer = javax.swing.Timer(120) {
        spinnerTick++
        if (spinnerTick % 40 == 0) thinkingWord = thinkingWords.random() // change the word ~every 4.8s
        updateStatus()
    }

    /** Request ids whose diff we've already triggered, so a tray rebuild doesn't reopen it every tick. */
    private val diffOpened = HashSet<String>()

    /** Open diff tabs by request id, so a resolved request's diff can be focused ("View diff") or closed. */
    private val diffFiles = HashMap<String, VirtualFile>()

    init {
        background = ChatTheme.BG
        add(transcript, BorderLayout.CENTER)
        add(buildComposer(), BorderLayout.SOUTH)

        installInputKeys()
        installShortcuts()

        session.transcript.addListener(transcript)
        session.addListener(this)
        session.transcript.entries.forEach(transcript::onAdded)
        refreshState()
        rebuildPermissionTray()
    }

    /** Card composer: trays (permissions, queue) on top, the dark input card, chips + Send beneath it. */
    private fun buildComposer(): JComponent {
        val inputScroll = JBScrollPane(input).apply {
            preferredSize = Dimension(0, JBUI.scale(42)) // half the previous height (was 84)
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        val chips = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(modelChip)
            add(modeChip)
            add(effortChip)
            add(thinkingChip)
        }
        val bottomBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
            add(chips, BorderLayout.CENTER)
            add(sendButton, BorderLayout.EAST)
        }

        val card = ChatTheme.RoundedPanel(14, ChatTheme.CARD_BG, ChatTheme.BORDER).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(8, 10)
            add(inputScroll, BorderLayout.CENTER)
            add(bottomBar, BorderLayout.SOUTH)
        }

        return JPanel(VerticalLayout(0)).apply {
            isOpaque = true
            background = ChatTheme.BG
            border = JBUI.Borders.empty(6, 18, 10, 18)
            add(quotaPanel)
            add(permissionTray)
            add(queueStrip)
            add(card)
            add(statusBar)
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

    private fun installInputKeys() {
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when {
                    e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown -> {
                        e.consume()
                        sendInput()
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

    private fun sendInput() {
        val text = input.text
        if (text.isBlank()) return
        input.text = ""
        // /btw <question>: client-side command (the SDK doesn't expose it) — send a side question mid-turn.
        val btw = Regex("^/btw\\b\\s*", RegexOption.IGNORE_CASE)
        if (btw.containsMatchIn(text)) {
            val question = text.replaceFirst(btw, "").trim()
            if (question.isNotEmpty()) {
                session.sendSideQuestion(question)
                return
            }
        }
        session.send(text)
    }

    /** Inserts the current editor file as an @-reference, like the CLI's @ mentions. */
    fun mentionCurrentFile() {
        val path = EditorContextProvider.currentFilePath(project) ?: return
        val prefix = if (input.text.isEmpty() || input.text.endsWith(" ")) "" else " "
        input.append("$prefix@$path ")
        input.requestFocusInWindow()
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
        updateQuotaBar()
        rebuildQueueStrip()
        modelChip.text = (session.model?.let { shortModel(it) } ?: "Default model") + "  ▾"
        modeChip.text = session.permissionMode + "  ▾"
        effortChip.text = "effort: ${session.effort ?: "default"}  ▾"
        thinkingChip.text = (session.thinkingTokens?.let { "thinking: ${it / 1000}k" } ?: "thinking: off") + "  ▾"
        sendButton.text = if (session.turnActive) "Stop" else "Send"
        if (session.turnActive) {
            if (!spinnerTimer.isRunning) spinnerTimer.start()
        } else {
            spinnerTimer.stop()
        }
        updateStatus()
    }

    /** Renders the animated "thinking" word and the live token count (left/right of the status bar). */
    private fun updateStatus() {
        if (session.turnActive) {
            val frame = spinnerFrames[spinnerTick % spinnerFrames.length]
            statusLabel.text = " $frame $thinkingWord…  (Esc to interrupt)"
            tokensLabel.text = if (session.liveOutputTokens > 0) "${session.liveOutputTokens} tokens " else ""
        } else {
            statusLabel.text = " "
            tokensLabel.text = ""
        }
    }

    /** Reflects the session's subscription quota: fill = utilization%, amber on warning, red when exhausted. */
    private fun updateQuotaBar() {
        val rl = session.rateLimit
        if (rl == null) {
            quotaPanel.isVisible = false
            return
        }
        quotaPanel.isVisible = true
        val pct = rl.utilizationPercent()
        val color = when {
            rl.isExhausted -> ChatTheme.ERROR
            rl.isWarning -> ChatTheme.WARNING
            else -> ChatTheme.ACCENT
        }
        quotaBar.foreground = color
        quotaBar.value = pct ?: if (rl.isExhausted) 100 else 0
        quotaBar.string = when {
            pct != null -> "$pct%"
            rl.isExhausted -> "exhausted"
            else -> "—"
        }
        val overage = if (rl.isUsingOverage) " · overage" else ""
        quotaLabel.text = (if (rl.isWarning) "⚠ " else "") + "Quota ${rl.windowLabel()}$overage"
        quotaLabel.foreground = if (rl.isWarning) color else ChatTheme.TEXT_DIM
        quotaPanel.toolTipText = rl.resetsAt?.let {
            "Resets ~" + java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        }
    }

    private fun shortModel(value: String): String =
        session.models.firstOrNull { it.value == value }?.displayName?.ifBlank { value } ?: value

    // -----------------------------------------------------------------------
    // Permission tray  (EDT)
    // -----------------------------------------------------------------------

    private fun rebuildPermissionTray() {
        permissionTray.removeAll()
        val pending = session.pendingPermissions()
        permissionTray.isVisible = pending.isNotEmpty()
        pending.forEach { request ->
            permissionTray.add(if (request.questions != null) questionCard(request) else permissionCard(request))
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
        permissionTray.revalidate()
        permissionTray.repaint()
    }

    private fun permissionCard(request: PendingPermission): JComponent {
        val titleLabel = JBLabel(request.title).apply {
            foreground = ChatTheme.TEXT
            font = ChatTheme.small.asBold()
        }
        val texts = JPanel(VerticalLayout(JBUI.scale(2))).apply {
            isOpaque = false
            add(titleLabel)
            if (request.summary.isNotBlank()) {
                add(JBLabel(truncate(request.summary, 90)).apply {
                    foreground = ChatTheme.TEXT_DIM
                    font = ChatTheme.mono
                })
            }
        }

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
        if (request.reviewable) {
            buttons.add(linkButton("View diff") {
                val file = diffFiles[request.requestId]
                if (file != null) DiffPresenter.revealDiff(project, file)
                else DiffPresenter.openDiff(project, request.toolName, request.input)
                    ?.let { diffFiles[request.requestId] = it }
            })
        }
        buttons.add(linkButton("Reject") { session.resolvePermission(request.requestId, allow = false) })
        buttons.add(RoundedActionButton("Accept") { session.resolvePermission(request.requestId, allow = true) })

        return ChatTheme.RoundedPanel(12, ChatTheme.CARD_BG, ChatTheme.ACCENT).apply {
            layout = BorderLayout(JBUI.scale(8), JBUI.scale(6))
            border = JBUI.Borders.empty(10, 12)
            add(texts, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
    }

    /**
     * AskUserQuestion card: renders each question's options as selectable rows (single- or multi-select),
     * with Submit/Cancel. Submit returns the chosen labels via [ClaudeSession.resolveQuestion]; Cancel denies.
     */
    private fun questionCard(request: PendingPermission): JComponent {
        val questions = request.questions ?: return permissionCard(request)
        val selections = LinkedHashMap<String, LinkedHashSet<String>>()
        val refreshers = ArrayList<() -> Unit>()

        val submit = RoundedActionButton("Submit") {
            if (selections.values.all { it.isNotEmpty() }) {
                session.resolveQuestion(request.requestId, selections.mapValues { (_, v) -> v.joinToString(",") })
            }
        }.apply { isEnabled = false }

        val body = JPanel(VerticalLayout(JBUI.scale(4))).apply { isOpaque = false }
        body.add(JBLabel(request.title).apply { foreground = ChatTheme.TEXT; font = ChatTheme.small.asBold() })

        for (q in questions) {
            val sel = LinkedHashSet<String>()
            selections[q.question] = sel
            if (q.question.isNotBlank()) {
                body.add(JBLabel(q.question).apply {
                    foreground = ChatTheme.TEXT
                    font = ChatTheme.small
                    border = JBUI.Borders.emptyTop(6)
                })
            }
            for (opt in q.options) {
                val button = JButton().apply {
                    isFocusable = false
                    isContentAreaFilled = false
                    isBorderPainted = false
                    isOpaque = false
                    horizontalAlignment = SwingConstants.LEFT
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    font = ChatTheme.small
                    margin = JBUI.insets(2, 4)
                }
                val refresh = {
                    val on = opt.label in sel
                    button.foreground = if (on) ChatTheme.TEXT else ChatTheme.TEXT_DIM
                    val desc = if (opt.description.isNotBlank()) "  —  ${opt.description}" else ""
                    button.text = "${if (on) "◉" else "◯"} ${opt.label}$desc"
                }
                refreshers.add(refresh)
                button.addActionListener {
                    if (q.multiSelect) { if (!sel.add(opt.label)) sel.remove(opt.label) }
                    else { sel.clear(); sel.add(opt.label) }
                    refreshers.forEach { it() }
                    submit.isEnabled = selections.values.all { it.isNotEmpty() }
                }
                refresh()
                body.add(button)
            }
        }

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
        buttons.add(linkButton("Cancel") { session.resolvePermission(request.requestId, allow = false) })
        buttons.add(submit)

        return ChatTheme.RoundedPanel(12, ChatTheme.CARD_BG, ChatTheme.ACCENT).apply {
            layout = BorderLayout(JBUI.scale(8), JBUI.scale(6))
            border = JBUI.Borders.empty(10, 12)
            add(body, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
    }

    private fun truncate(s: String, max: Int): String =
        s.replace('\n', ' ').let { if (it.length > max) it.take(max) + "…" else it }

    // -----------------------------------------------------------------------
    // queued (multiprompt) strip
    // -----------------------------------------------------------------------

    private fun rebuildQueueStrip() {
        queueStrip.removeAll()
        val queued = session.queuedPrompts()
        queueStrip.isVisible = queued.isNotEmpty()
        queued.forEachIndexed { index, prompt -> queueStrip.add(queueRow(index, prompt)) }
        queueStrip.revalidate()
        queueStrip.repaint()
    }

    private fun queueRow(index: Int, prompt: String): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(JBLabel("⏳").apply { foreground = ChatTheme.TEXT_DIM })
            add(JBLabel(truncate(prompt, 70)).apply { foreground = ChatTheme.TEXT_DIM })
            add(linkButton("✕") { session.removeQueued(index) })
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
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        margin = JBUI.insets(2, 4)
        addActionListener { onClick(this) }
    }

    private fun linkButton(text: String, onClick: () -> Unit): JButton = JButton(text).apply {
        isFocusable = false
        isContentAreaFilled = false
        isBorderPainted = false
        isOpaque = false
        foreground = ChatTheme.TEXT_DIM
        font = ChatTheme.small
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        margin = JBUI.insets(2, 8)
        addActionListener { onClick() }
    }

    private fun JComponent.popup(group: DefaultActionGroup) {
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            null, group, DataContext.EMPTY_CONTEXT,
            JBPopupFactory.ActionSelectionAid.MNEMONICS, true,
        )
        // Los chips están al fondo del tool window: anclar ENCIMA del chip, no debajo
        // (showUnderneathOf lo empujaría fuera del borde / a la esquina inferior-izquierda).
        popup.show(RelativePoint(this, java.awt.Point(0, -popup.content.preferredSize.height)))
    }

    override fun dispose() {
        spinnerTimer.stop()
        session.transcript.removeListener(transcript)
        session.removeListener(this)
    }

    /** A compact, coral-filled pill button (Send / Stop / Accept). */
    private class RoundedActionButton(text: String, private val onClick: () -> Unit) : JButton(text) {
        init {
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            isOpaque = false
            foreground = ChatTheme.ACCENT_TEXT
            font = ChatTheme.small.asBold()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = JBUI.insets(5, 14)
            addActionListener { onClick() }
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = when {
                    !isEnabled -> ChatTheme.ACCENT.darker().darker()
                    model.isPressed -> ChatTheme.ACCENT.darker()
                    else -> ChatTheme.ACCENT
                }
                val arc = JBUI.scale(14)
                g2.fillRoundRect(0, 0, width, height, arc, arc)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }
}
