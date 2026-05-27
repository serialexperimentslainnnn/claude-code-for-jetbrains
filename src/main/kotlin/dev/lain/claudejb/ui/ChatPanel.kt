package dev.lain.claudejb.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
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
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.lain.claudejb.context.EditorContextProvider
import dev.lain.claudejb.diff.DiffPresenter
import dev.lain.claudejb.diff.HunkSelection
import dev.lain.claudejb.permission.PendingPermission
import dev.lain.claudejb.protocol.AskOption
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
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.KeyStroke
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager

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

    private val permissionTray = JPanel(VerticalLayout(JBUIScale.scale(6))).apply {
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

    // Subscription quota shown above the prompt as two text lines.
    private val quotaLabel = JBLabel().apply {  // line 1: "Resets in Xh Ym" / warning
        font = ChatTheme.small
        foreground = ChatTheme.TEXT_DIM
    }
    private val resetHourLabel = JBLabel().apply {  // line 2: "Reset Hour: HH:mm"
        font = ChatTheme.small
        foreground = ChatTheme.TEXT_DIM
    }
    private val usageLabel = JBLabel().apply {  // line 3: "Session Usage: X%"
        font = ChatTheme.small
        foreground = ChatTheme.TEXT_DIM
    }
    private val quotaPanel = JPanel(VerticalLayout(0)).apply {
        isOpaque = false
        border = JBUI.Borders.emptyBottom(6)
        add(quotaLabel)
        add(resetHourLabel)
        add(usageLabel)
        isVisible = false
    }

    // option chips under the prompt — reflect the live session and open their popups on click
    private val modelChip = chip { it.popup(OptionMenus.modelGroup(session)) }
    private val modeChip = chip { it.popup(OptionMenus.permissionModeGroup(session)) }
    private val effortChip = chip { it.popup(OptionMenus.effortGroup(session)) }
    private val thinkingChip = chip { it.popup(OptionMenus.thinkingGroup(session)) }

    private val sendButton = SendIconButton { sendOrStop() }

    private val statusLabel = JBLabel().apply {
        foreground = ChatTheme.TEXT_DIM
        // Match the quota lines' typography so the spinner/token meter sits on the same baseline as the
        // reset timers above it, instead of dropping lower with a larger font.
        font = ChatTheme.small
        horizontalAlignment = SwingConstants.LEADING
        verticalAlignment = SwingConstants.TOP
    }

    // CLI-style "thinking" spinner: a rotating gerund + braille frames, animated only while a turn is active.
    private val thinkingWords = listOf(
        "Pondering", "Cogitating", "Conjuring", "Musing", "Ruminating", "Brewing", "Synthesizing",
        "Percolating", "Noodling", "Marinating", "Computing", "Forging", "Distilling", "Scheming",
    )
    private val spinnerFrames = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
    private var spinnerTick = 0
    private var thinkingWord = thinkingWords.random()
    private val spinnerTimer = javax.swing.Timer(SPINNER_INTERVAL_MS) {
        spinnerTick++
        if (spinnerTick % SPINNER_WORD_CHANGE_TICKS == 0) thinkingWord = thinkingWords.random() // change the word ~every 4.8s
        updateStatus()
    }
    /**
     * Every minute: refresh the "Resets in Xh Ym" countdown locally AND ask the binary for fresh
     * session data. The binary may emit a rate_limit_event in response, which updates rateLimit and
     * triggers another updateQuotaBar via the session listener.
     */
    private val quotaRefreshTimer = javax.swing.Timer(QUOTA_REFRESH_MS) {
        updateQuotaBar()
        session.requestSessionCost { updateQuotaBar() }
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
        session.transcript.entries.forEachIndexed { index, entry -> transcript.onAdded(entry, index) }
        refreshState()
        rebuildPermissionTray()
        quotaRefreshTimer.start()
        session.requestSessionCost { updateQuotaBar() }
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

        val chips = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(modelChip)
            add(modeChip)
            add(effortChip)
            add(thinkingChip)
        }

        val optionsBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(3, 8, 3, 4)
            add(chips, BorderLayout.CENTER)
            add(sendButton, BorderLayout.EAST)
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
                    g2.color = ChatTheme.BORDER
                    g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc)
                } finally {
                    g2.dispose()
                }
            }
        }
        card.add(inputWrapper, BorderLayout.CENTER)
        card.add(optionsBar, BorderLayout.SOUTH)

        return JPanel(VerticalLayout(0)).apply {
            isOpaque = true
            background = ChatTheme.BG
            border = JBUI.Borders.empty(2, 18, 10, 18)
            add(quotaPanel)
            add(statusLabel)
            add(permissionTray)
            add(queueStrip)
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
        modelChip.text = "Model: ${session.model?.let { shortModel(it) } ?: "Opus 4.7"}  ▾"
        modeChip.text = "Mode: ${session.permissionMode}  ▾"
        effortChip.text = "effort: ${session.effort ?: "default"}  ▾"
        thinkingChip.text = "thinking: " + (if (session.thinkingTokens != null) "on" else "off") + "  ▾"
        sendButton.isStopMode = session.turnActive
        if (session.turnActive) {
            if (!spinnerTimer.isRunning) spinnerTimer.start()
        } else {
            spinnerTimer.stop()
        }
        updateStatus()
    }

    /** Renders the animated "thinking" word and live token count in a single right-aligned label. */
    private fun updateStatus() {
        if (session.turnActive) {
            val frame = spinnerFrames[spinnerTick % spinnerFrames.length]
            val total = session.sessionTokens + session.liveOutputTokens
            val tokens = if (total > 0) " — $total tokens" else ""
            statusLabel.text = "$frame $thinkingWord…$tokens  (Esc to interrupt) "
        } else {
            statusLabel.text = " "
        }
    }

    /**
     * Reflects the session's subscription quota. The reset countdown + reset hour show whenever the binary
     * reports a window (`resetsAt`); the "% usage" meter is shown only when utilization is actually reported
     * (e.g. near the limit / overage). On a plan with plenty of headroom (Max x20) the % simply drops out while
     * the reset info stays. The whole bar hides only when there is nothing meaningful to show.
     */
    private fun updateQuotaBar() {
        val rl = session.rateLimit
        val pct = rl?.utilizationPercent() ?: if (rl?.isExhausted == true) 100 else null
        val hasReset = rl?.resetsAt != null
        if (rl == null || (pct == null && !hasReset && !rl.isWarning)) {
            if (quotaPanel.isVisible) { quotaPanel.isVisible = false; quotaPanel.parent?.revalidate(); quotaPanel.parent?.repaint() }
            return
        }
        val wasHidden = !quotaPanel.isVisible
        quotaPanel.isVisible = true

        val overage = if (rl.isUsingOverage) " · overage" else ""
        val usageColor = when (pct) {
            null                            -> ChatTheme.TEXT_DIM
            in 0..QUOTA_LOW_MAX             -> Color(0x4CAF50)
            in QUOTA_LOW_MAX + 1..QUOTA_MID_MAX  -> Color(0xFFD54F)
            in QUOTA_MID_MAX + 1..QUOTA_HIGH_MAX -> Color(0xFF7043)
            else                            -> Color(0xEF5350)
        }

        // Line 1 — reset countdown (+ overage/warning). Present whenever there is a window.
        val resetStr = rl.resetsAt?.let {
            val remaining = it - System.currentTimeMillis() / 1000
            if (remaining > 0) {
                val h = remaining / 3600
                val m = (remaining % 3600) / 60
                "Resets in ${if (h > 0L) "${h}h " else ""}${m}m"
            } else "Resetting soon"
        }
        quotaLabel.isVisible = resetStr != null || rl.isWarning
        quotaLabel.text = (if (rl.isWarning) "⚠ " else "") + (resetStr ?: "") + overage
        quotaLabel.foreground = if (rl.isWarning) usageColor else ChatTheme.TEXT_DIM

        // Line 2 — reset hour. Present whenever resetsAt is known.
        val resetHour = rl.resetsAt?.let {
            java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        }
        resetHourLabel.isVisible = resetHour != null
        resetHourLabel.text = resetHour?.let { "Reset Hour: $it" } ?: ""
        resetHourLabel.foreground = ChatTheme.TEXT_DIM

        // Line 3 — session usage %. Only shown when the binary reports utilization (near limit / overage).
        usageLabel.isVisible = pct != null
        usageLabel.text = if (pct != null) "Session Usage: ${if (rl.isExhausted) "exhausted" else "$pct%"}" else ""
        usageLabel.foreground = usageColor

        if (wasHidden) { quotaPanel.parent?.revalidate(); quotaPanel.parent?.repaint() }
    }

    private fun shortModel(value: String): String =
        session.models.firstOrNull { it.value == value }?.displayName?.ifBlank { null }
            ?: value.removePrefix("claude-").replace("-", " ").replaceFirstChar { it.uppercaseChar() }

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
        val texts = JPanel(VerticalLayout(JBUIScale.scale(2))).apply {
            isOpaque = false
            add(titleLabel)
            if (request.summary.isNotBlank()) {
                val area = JBTextArea(request.summary).apply {
                    isEditable = false
                    lineWrap = false
                    foreground = ChatTheme.TEXT_DIM
                    font = ChatTheme.mono
                    background = ChatTheme.CODE_BG
                    isOpaque = true
                    border = JBUI.Borders.empty(3, 6)
                }
                val naturalH = (area.preferredSize.height + JBUIScale.scale(6)).coerceAtMost(JBUIScale.scale(PERMISSION_SUMMARY_MAX_HEIGHT))
                add(JBScrollPane(area).apply {
                    border = JBUI.Borders.empty()
                    background = ChatTheme.CODE_BG
                    viewport.background = ChatTheme.CODE_BG
                    preferredSize = Dimension(0, naturalH)
                    verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                    horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
                })
            }
        }

        // Per-hunk selection: for a reviewable write with >1 hunk, let the user pick which hunks to apply.
        // Computed once at card-build time so toggling doesn't re-read the file / recompute the diff.
        val hunkData: HunkSelectorData? = if (request.reviewable) {
            val path = DiffPresenter.filePathOf(request.input)
            val current = path?.let {
                val f = java.io.File(it)
                if (f.isFile) runCatching { f.readText() }.getOrDefault("") else ""
            } ?: ""
            val proposed = DiffPresenter.proposedContent(request.toolName, request.input, current)
            val hunks = if (proposed != null) DiffPresenter.computeHunks(current, proposed) else emptyList()
            if (hunks.size > 1 && proposed != null) HunkSelectorData(current, proposed, hunks) else null
        } else null

        val accepted: LinkedHashSet<Int>? = hunkData?.hunks?.mapTo(LinkedHashSet()) { it.index }

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
        if (request.reviewable) {
            buttons.add(linkButton("View diff") {
                val file = diffFiles[request.requestId]
                if (file != null) DiffPresenter.revealDiff(project, file)
                else DiffPresenter.openDiff(project, request.toolName, request.input)
                    ?.let { diffFiles[request.requestId] = it }
            })
        }
        buttons.add(linkButton("Always allow") {
            ClaudeSettings.getInstance(project).rememberToolAlwaysAllow(request.toolName)
            session.resolvePermission(request.requestId, allow = true)
        })
        buttons.add(linkButton("Reject") { session.resolvePermission(request.requestId, allow = false) })
        buttons.add(RoundedActionButton("Accept") {
            if (hunkData != null && accepted != null) {
                val allIndices = hunkData.hunks.mapTo(HashSet()) { it.index }
                when {
                    accepted.isEmpty() ->
                        // Nothing selected: applying an empty subset would be a confusing no-op write, so reject.
                        session.resolvePermission(request.requestId, allow = false)
                    accepted == allIndices ->
                        session.resolvePermission(request.requestId, allow = true)
                    else -> {
                        val selectedText = HunkSelection.reconstruct(
                            hunkData.current.split("\n"), hunkData.proposed.split("\n"), hunkData.hunks, accepted,
                        )
                        val override = HunkSelection.encodeInput(
                            request.toolName, request.input, hunkData.current, selectedText,
                        )
                        session.resolvePermission(request.requestId, allow = true, overrideInput = override)
                    }
                }
            } else {
                session.resolvePermission(request.requestId, allow = true)
            }
        })

        // Summary (+ optional hunk selector) live together in the CENTER region of the card's BorderLayout.
        val center = if (hunkData != null && accepted != null) {
            JPanel(VerticalLayout(JBUIScale.scale(6))).apply {
                isOpaque = false
                add(texts)
                add(hunkSelector(hunkData.hunks, accepted))
            }
        } else texts

        return ChatTheme.RoundedPanel(12, ChatTheme.CARD_BG, ChatTheme.ACCENT).apply {
            layout = BorderLayout(JBUIScale.scale(8), JBUIScale.scale(6))
            border = JBUI.Borders.empty(10, 12)
            add(center, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
    }

    /** Diff data captured once when a per-hunk permission card is built (no re-read on toggle). */
    private data class HunkSelectorData(
        val current: String,
        val proposed: String,
        val hunks: List<dev.lain.claudejb.diff.Hunk>,
    )

    /**
     * A height-capped, scrollable list of the change's hunks as checkbox rows (all checked by default). Toggling a
     * row mutates [accepted] in place and refreshes its glyph; the Accept button reads [accepted] when clicked.
     * Reuses the [optionRow] multi-select glyph style (☑/☐).
     */
    private fun hunkSelector(hunks: List<dev.lain.claudejb.diff.Hunk>, accepted: LinkedHashSet<Int>): JComponent {
        val list = JPanel(VerticalLayout(JBUIScale.scale(2))).apply { isOpaque = false }
        for (hunk in hunks) {
            val glyph = JBLabel().apply {
                font = ChatTheme.small
                verticalAlignment = SwingConstants.TOP
                border = JBUI.Borders.emptyRight(8)
            }
            val text = "Lines ${hunk.start1 + 1}-${hunk.end1}: ${truncate(hunk.preview, HUNK_PREVIEW_MAX_CHARS)}"
            val label = wrappingArea(text, ChatTheme.small, ChatTheme.TEXT)
            val refresh = {
                val on = hunk.index in accepted
                glyph.text = if (on) "☑" else "☐"
                label.foreground = if (on) ChatTheme.TEXT else ChatTheme.TEXT_DIM
            }
            val toggle = {
                if (!accepted.add(hunk.index)) accepted.remove(hunk.index)
                refresh()
            }
            val click = object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = toggle()
            }
            val row = JPanel(BorderLayout()).apply {
                isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(2, 4)
                add(glyph, BorderLayout.WEST)
                add(label, BorderLayout.CENTER)
                addMouseListener(click)
            }
            glyph.addMouseListener(click)
            label.addMouseListener(click)
            refresh()
            list.add(row)
        }
        return JBScrollPane(list).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            preferredSize = Dimension(0, JBUIScale.scale(PERMISSION_SUMMARY_MAX_HEIGHT))
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
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

        val body = JPanel(VerticalLayout(JBUIScale.scale(4))).apply { isOpaque = false }
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
                val toggle = {
                    if (q.multiSelect) { if (!sel.add(opt.label)) sel.remove(opt.label) }
                    else { sel.clear(); sel.add(opt.label) }
                    refreshers.forEach { it() }
                    submit.isEnabled = selections.values.all { it.isNotEmpty() }
                }
                val (row, refresh) = optionRow(opt, q.multiSelect, isSelected = { opt.label in sel }, onToggle = toggle)
                refreshers.add(refresh)
                refresh()
                body.add(row)
            }
        }

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
        buttons.add(linkButton("Cancel") { session.resolvePermission(request.requestId, allow = false) })
        buttons.add(submit)

        return ChatTheme.RoundedPanel(12, ChatTheme.CARD_BG, ChatTheme.ACCENT).apply {
            layout = BorderLayout(JBUIScale.scale(8), JBUIScale.scale(6))
            border = JBUI.Borders.empty(10, 12)
            add(body, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
    }

    /**
     * One AskUserQuestion option as a wrapping, fully clickable row: a selection glyph plus the label, description
     * and (when present) preview, each in a word-wrapping area so long text stays readable inside the narrow tool
     * window instead of being clipped to one line. Returns the row and a refresher that syncs glyph/colors.
     */
    private fun optionRow(
        opt: AskOption,
        multiSelect: Boolean,
        isSelected: () -> Boolean,
        onToggle: () -> Unit,
    ): Pair<JComponent, () -> Unit> {
        val glyph = JBLabel().apply {
            font = ChatTheme.small
            verticalAlignment = SwingConstants.TOP
            border = JBUI.Borders.emptyRight(8)
        }
        val label = wrappingArea(opt.label, ChatTheme.small.asBold(), ChatTheme.TEXT)
        val center = JPanel(VerticalLayout(JBUIScale.scale(2))).apply {
            isOpaque = false
            add(label)
            if (opt.description.isNotBlank()) {
                add(wrappingArea(opt.description, ChatTheme.small, ChatTheme.TEXT_DIM))
            }
            opt.preview?.takeIf { it.isNotBlank() }?.let { preview ->
                val area = JBTextArea(preview).apply {
                    isEditable = false
                    lineWrap = false
                    font = ChatTheme.mono
                    foreground = ChatTheme.TEXT_DIM
                    background = ChatTheme.CODE_BG
                    isOpaque = true
                    border = JBUI.Borders.empty(3, 6)
                }
                val naturalH = (area.preferredSize.height + JBUIScale.scale(6))
                    .coerceAtMost(JBUIScale.scale(PERMISSION_SUMMARY_MAX_HEIGHT))
                add(JBScrollPane(area).apply {
                    border = JBUI.Borders.empty()
                    background = ChatTheme.CODE_BG
                    viewport.background = ChatTheme.CODE_BG
                    preferredSize = Dimension(0, naturalH)
                    verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                    horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
                })
            }
        }
        val row = JPanel(BorderLayout()).apply {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(3, 4)
            add(glyph, BorderLayout.WEST)
            add(center, BorderLayout.CENTER)
        }
        // The whole row toggles; attach to the row and to the text components (which would otherwise swallow the
        // click). The description/preview keep text selection but the click still toggles via these listeners.
        val click = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onToggle()
        }
        row.addMouseListener(click)
        glyph.addMouseListener(click)
        label.addMouseListener(click)
        val refresh = {
            val on = isSelected()
            glyph.text = if (multiSelect) (if (on) "☑" else "☐") else (if (on) "◉" else "◯")
            label.foreground = if (on) ChatTheme.TEXT else ChatTheme.TEXT_DIM
        }
        return row to refresh
    }

    /**
     * A non-editable, word-wrapping text area. JBTextArea (not a JLabel) so the text is taken literally — no HTML
     * escaping of labels/descriptions. Before the first layout the area has width 0 and would report a one-line
     * height (collapsing the text); falling back to the parent's width makes the wrapped height correct on the
     * first pass too.
     */
    private fun wrappingArea(content: String, font: JBFont, color: Color): JBTextArea =
        object : JBTextArea(content) {
            override fun getPreferredSize(): Dimension {
                if (width == 0) parent?.width?.takeIf { it > 0 }?.let { setSize(it, Short.MAX_VALUE.toInt()) }
                return super.getPreferredSize()
            }
        }.apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            this.font = font
            foreground = color
            border = JBUI.Borders.empty()
            margin = JBUI.emptyInsets()
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
            add(JBLabel(truncate(prompt, QUEUE_PROMPT_MAX_CHARS)).apply { foreground = ChatTheme.TEXT_DIM })
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
        // The chips sit at the bottom of the tool window: anchor ABOVE the chip, not below
        // (showUnderneathOf would push it off the edge / into the bottom-left corner).
        popup.show(RelativePoint(this, java.awt.Point(0, -popup.content.preferredSize.height)))
    }

    override fun dispose() {
        spinnerTimer.stop()
        quotaRefreshTimer.stop()
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

                if (model.isRollover || model.isPressed) {
                    val alpha = if (model.isPressed) 50 else 25
                    g2.color = Color(255, 255, 255, alpha)
                    g2.fillRoundRect(2, 2, w - 4, h - 4, JBUIScale.scale(6), JBUIScale.scale(6))
                }

                val iconAlpha = if (isEnabled) 210 else 80
                g2.stroke = BasicStroke(JBUIScale.scale(1.5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

                if (isStopMode) {
                    g2.color = Color(150, 150, 150, iconAlpha)
                    val sq = JBUIScale.scale(11)
                    g2.drawRoundRect((w - sq) / 2, (h - sq) / 2, sq, sq, JBUIScale.scale(3), JBUIScale.scale(3))
                } else {
                    g2.color = Color(200, 200, 200, iconAlpha)
                    val padX = JBUIScale.scale(9)
                    val padY = JBUIScale.scale(9)
                    g2.drawPolygon(
                        intArrayOf(w - padX, padX, padX),
                        intArrayOf(h / 2,    padY, h - padY),
                        3
                    )
                }
            } finally {
                g2.dispose()
            }
        }
    }

    /** A compact pill button (Send / Stop / Accept) painted with the IDE's primary button color. */
    private class RoundedActionButton(text: String, private val onClick: () -> Unit) : JButton(text) {
        init {
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            isOpaque = false
            font = ChatTheme.small.asBold()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = JBUI.insets(5, 14)
            addActionListener { onClick() }
        }

        override fun getForeground(): Color =
            UIManager.getColor("Button.default.foreground") ?: Color.WHITE

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val base: Color = UIManager.getColor("Button.default.startBackground") ?: Color(0x4B6EAF)
                g2.color = when {
                    !isEnabled -> base.darker()
                    model.isPressed -> base.darker()
                    else -> base
                }
                val arc = JBUIScale.scale(14)
                g2.fillRoundRect(0, 0, width, height, arc, arc)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    companion object {
        /** Spinner animation tick interval (ms). */
        private const val SPINNER_INTERVAL_MS = 120
        /** Spinner ticks between thinking-word changes (~4.8s at [SPINNER_INTERVAL_MS]). */
        private const val SPINNER_WORD_CHANGE_TICKS = 40
        /** Interval (ms) for the quota countdown refresh + session-cost poll. */
        private const val QUOTA_REFRESH_MS = 60_000

        // Quota usage % thresholds (inclusive upper bounds) that drive the meter color.
        private const val QUOTA_LOW_MAX = 20   // 0..20  → green
        private const val QUOTA_MID_MAX = 50   // 21..50 → amber
        private const val QUOTA_HIGH_MAX = 80  // 51..80 → orange; above → red

        /** Max height (unscaled) of a permission card's summary box before it scrolls. */
        private const val PERMISSION_SUMMARY_MAX_HEIGHT = 82
        /** Max characters of a queued prompt shown in the queue strip before truncation. */
        private const val QUEUE_PROMPT_MAX_CHARS = 70
        /** Max characters of a hunk preview shown in a per-hunk selector row before truncation. */
        private const val HUNK_PREVIEW_MAX_CHARS = 80
    }
}
