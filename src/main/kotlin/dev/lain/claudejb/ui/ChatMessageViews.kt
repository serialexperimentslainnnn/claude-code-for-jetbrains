package dev.lain.claudejb.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import dev.lain.claudejb.session.Speaker
import dev.lain.claudejb.session.ToolState
import dev.lain.claudejb.session.TranscriptEntry
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Insets
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextPane
import javax.swing.SwingConstants
import javax.swing.event.HyperlinkEvent
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet

/**
 * One transcript entry rendered as a claude.ai-style card. Each [Speaker] gets its own shape:
 * a coral-avatared assistant block with rendered Markdown, a rounded bubble for the user, a collapsible
 * "Thought process" for thinking, a compact card for tool calls, and quiet inline notices for system/error.
 *
 * Streaming entries (assistant text, thinking) grow via [update]; the others are written once.
 */
sealed class MessageRow(val entryId: Long) : JPanel(BorderLayout()) {
    /** Row padding; subclasses tweak it (e.g. tool outputs sit tighter to their call). */
    protected open val baseInsets: Insets get() = Insets(5, 12, 5, 12)

    init {
        isOpaque = false
        val i = baseInsets
        border = JBUI.Borders.empty(i.top, i.left, i.bottom, i.right)
    }

    abstract fun update(text: String, meta: String?)

    /** Show/hide reasoning. No-op except for [ThinkingRow]; driven by the Ctrl+O shortcut. */
    open fun setReasoningVisible(visible: Boolean) {}

    companion object {
        fun create(entry: TranscriptEntry): MessageRow = when (entry.speaker) {
            Speaker.USER -> UserRow(entry.id)
            Speaker.ASSISTANT -> AssistantRow(entry.id)
            Speaker.THINKING -> ThinkingRow(entry.id)
            Speaker.TOOL -> ToolRow(entry.id)
            Speaker.TOOL_OUTPUT -> ToolOutputRow(entry.id)
            Speaker.SYSTEM -> SystemRow(entry.id)
            Speaker.ERROR -> ErrorRow(entry.id)
        }.also { it.update(entry.text, entry.meta) }
    }
}

private class UserRow(id: Long) : MessageRow(id) {
    private val content = HtmlContent()

    init {
        // AI Assistant style: a full-width block with a "You" role label, lightly tinted — not a chat bubble.
        val header = JBLabel("You").apply {
            foreground = ChatTheme.TEXT_DIM
            font = ChatTheme.small.asBold()
            border = JBUI.Borders.emptyBottom(3)
        }
        val block = ChatTheme.RoundedPanel(10, ChatTheme.USER_BUBBLE, shadow = true).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(10, 14)
            add(header, BorderLayout.NORTH)
            add(content, BorderLayout.CENTER)
        }
        add(block, BorderLayout.CENTER)
    }

    override fun update(text: String, meta: String?) = content.setMarkdown(text)
}

private class AssistantRow(id: Long) : MessageRow(id) {
    private val content = HtmlContent()
    private var raw = ""

    init {
        val name = JBLabel("Claude").apply {
            foreground = ChatTheme.TEXT_DIM
            font = ChatTheme.small
        }
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(ChatTheme.avatarLabel())
            add(name)
        }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(3)
            add(left, BorderLayout.WEST)
            add(iconButton(AllIcons.Actions.Copy, "Copy message") {
                CopyPasteManager.getInstance().setContents(StringSelection(raw))
            }, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
    }

    override fun update(text: String, meta: String?) {
        raw = text
        content.setMarkdown(text)
    }
}

private class ThinkingRow(id: Long) : MessageRow(id) {
    private val content = HtmlContent(dim = true)
    private val toggle = JBLabel().apply {
        foreground = ChatTheme.TEXT_DIM
        font = ChatTheme.small
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
    private val body = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4, 8, 0, 0)
        add(content, BorderLayout.CENTER)
    }
    private var expanded = true

    init {
        syncToggle()
        toggle.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = setReasoningVisible(!expanded)
        })
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(toggle, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
    }

    private fun syncToggle() {
        toggle.text = (if (expanded) "▾ " else "▸ ") + "Thought process"
    }

    override fun setReasoningVisible(visible: Boolean) {
        expanded = visible
        body.isVisible = visible
        syncToggle()
        revalidate()
    }

    override fun update(text: String, meta: String?) = content.setMarkdown(text)
}

class ToolRow(id: Long) : MessageRow(id) {
    // JTextArea (not a label): a long Bash command wraps onto multiple lines and stays fully readable
    // instead of being clipped to one line with an ellipsis.
    private val label = JTextArea().apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        font = ChatTheme.mono
        foreground = ChatTheme.TEXT
        border = JBUI.Borders.empty()
        margin = Insets(0, 0, 0, 0)
    }
    private val icon = JLabel().apply {
        border = JBUI.Borders.emptyRight(8)
        verticalAlignment = SwingConstants.TOP // stay aligned with the first line of a multi-line command
    }
    // Disclosure triangle: collapses/expands this tool's child rows (output, and a subagent's nested activity).
    // Hidden until the tool actually has children, so a childless call shows no useless toggle.
    private val toggle = JBLabel().apply {
        foreground = ChatTheme.TEXT_DIM
        font = ChatTheme.small
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.emptyRight(6)
        verticalAlignment = SwingConstants.TOP
        isVisible = false
    }
    // "Diff" affordance, shown only for reviewable file-writing tools (Edit/Write/MultiEdit). Clicking re-opens
    // the old↔new diff for this tool's pre-write snapshot in an editor tab, via the injected [onViewDiff] hook.
    private val diffButton = iconButton(AllIcons.Actions.Diff, "View diff") {
        toolUseId?.let { onViewDiff?.invoke(it) }
    }.apply { isVisible = false }
    // "Revert" affordance, shown alongside the diff button for the same reviewable file-writing tools. Clicking
    // asks the view (via the injected [onRevert] hook) to roll back this tool's edit using its pre-write snapshot.
    private val revertButton = iconButton(AllIcons.Actions.Rollback, "Revert this edit") {
        toolUseId?.let { onRevert?.invoke(it) }
    }.apply { isVisible = false }
    // Elapsed-time chip, shown while the tool is RUNNING (the protocol carries no completion %, so we surface time).
    private val elapsedLabel = JBLabel().apply {
        foreground = ChatTheme.TEXT_DIM
        font = ChatTheme.small
        border = JBUI.Borders.emptyRight(6)
        verticalAlignment = SwingConstants.TOP
        isVisible = false
    }
    private val card = ChatTheme.RoundedPanel(10, ChatTheme.CARD_BG, ChatTheme.BORDER, shadow = true).apply {
        layout = BorderLayout()
        border = JBUI.Borders.empty(7, 11)
        add(toggle, BorderLayout.WEST)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(icon, BorderLayout.WEST)
            add(label, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        // Action cluster: a little breathing room between each affordance so the (now padded) Diff/Revert targets
        // don't touch and are each easy to hit.
        add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUIScale.scale(3), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(8)
            add(elapsedLabel)
            add(diffButton)
            add(revertButton)
        }, BorderLayout.EAST)
    }

    // Pulse animation for the RUNNING state: the box border fades between sky-blue and amber for a sense of motion.
    // The phase is global (driven by the single shared [PulseDriver] timer) so every in-flight row pulses in sync;
    // this row only registers/unregisters with the driver as it enters/leaves the in-flight state.
    private var pulsing = false

    /** Called by [PulseDriver] each frame while this row is in-flight: recolor the border to the global phase. */
    internal fun applyPulse() {
        card.line = pulseColor(PulseDriver.phase)
    }

    private fun startPulsing() {
        if (!pulsing) {
            pulsing = true
            PulseDriver.register(this)
        }
    }

    private fun stopPulsing() {
        if (pulsing) {
            pulsing = false
            PulseDriver.unregister(this)
        }
    }

    // Physical container for this tool's outputs and nested (subagent) activity. Children live *inside* the
    // tool item, so collapsing simply hides this panel and arrival order is preserved by append — no flat-list
    // index juggling that let late-arriving outputs scatter under unrelated rows.
    private val childrenPanel = JPanel(VerticalLayout(JBUIScale.scale(2))).apply {
        isOpaque = false
        border = JBUI.Borders.emptyLeft(JBUIScale.scale(14))
        isVisible = false // outputs start collapsed
    }

    // Outputs are collapsed by default; the user expands a tool to inspect its result.
    private var expanded = false
    private var hasChildren = false

    /** Invoked after the user toggles, so the view can re-lay out and keep the scroll pinned. */
    var onToggle: (() -> Unit)? = null

    /**
     * The `tool_use_id` of this tool call, set by the view once known. Drives the "View diff" button's payload
     * (and, together with the reviewable tool name in [update], its visibility). Null for childless history rows
     * whose id was never recorded — the button then stays hidden, never firing a no-op.
     */
    var toolUseId: String? = null
        set(value) {
            field = value
            refreshActionButtons()
        }

    /**
     * Reopens the diff for a reviewable edit. Wired by the view (e.g. ChatPanel/TranscriptView) to the session's
     * snapshot-backed [dev.lain.claudejb.diff.DiffPresenter.openDiff]; left null in contexts without a session so
     * the button never appears. Receives this row's `tool_use_id`.
     */
    var onViewDiff: ((toolUseId: String) -> Unit)? = null
        set(value) {
            field = value
            refreshActionButtons()
        }

    /**
     * Reverts a reviewable edit. Wired by the view (e.g. ChatPanel/TranscriptView) to roll back this tool's write
     * from its pre-write snapshot; left null in contexts without a session so the button never appears. Receives
     * this row's `tool_use_id`.
     */
    var onRevert: ((toolUseId: String) -> Unit)? = null
        set(value) {
            field = value
            refreshActionButtons()
        }

    private var toolName: String? = null

    /** The Diff/Revert buttons show only for a reviewable tool that has both a usable id and a wired handler. */
    private fun refreshActionButtons() {
        val reviewable = toolName in dev.lain.claudejb.diff.DiffPresenter.REVIEWABLE_TOOLS && !toolUseId.isNullOrBlank()
        diffButton.isVisible = reviewable && onViewDiff != null
        revertButton.isVisible = reviewable && onRevert != null
    }

    /** Append a child row (output or nested activity) inside this tool item and reveal the disclosure toggle. */
    fun addChild(row: JComponent) {
        childrenPanel.add(row)
        if (!hasChildren) {
            hasChildren = true
            toggle.isVisible = true
            syncToggle()
        }
    }

    init {
        syncToggle()
        // The whole tool card is the click target (not just the disclosure triangle), so a tap anywhere on the
        // box expands/collapses its output. The same listener is attached to the inner label and icon, which
        // would otherwise swallow the click before it reached the card.
        val toggleListener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = toggleExpanded()
        }
        card.addMouseListener(toggleListener)
        label.addMouseListener(toggleListener)
        icon.addMouseListener(toggleListener)
        toggle.addMouseListener(toggleListener)
        card.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(card, BorderLayout.NORTH)
            add(childrenPanel, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
    }

    private fun toggleExpanded() {
        if (!hasChildren) return
        expanded = !expanded
        childrenPanel.isVisible = expanded
        syncToggle()
        onToggle?.invoke()
    }

    private fun syncToggle() {
        toggle.text = if (expanded) "▾" else "▸"
    }

    override fun update(text: String, meta: String?) {
        label.text = text
        icon.icon = iconForTool(meta)
        toolName = meta
        refreshActionButtons()
    }

    /**
     * Reflects the tool's lifecycle on the box: LOADING → static sky-blue border; RUNNING → border pulses between
     * sky-blue and amber (sense of motion) + an elapsed-time chip; FINISHED → static green border. Called by the
     * view from the entry's [dev.lain.claudejb.session.ToolState].
     */
    fun setState(state: ToolState, elapsedSeconds: Double) {
        when (state) {
            // In-flight (LOADING or RUNNING): pulse the border between sky-blue and amber the WHOLE time the tool
            // works — we start the animation immediately on LOADING rather than waiting for a tool_progress
            // heartbeat (the binary doesn't emit those for most tools). RUNNING just adds the elapsed-time chip.
            ToolState.LOADING -> {
                startPulsing()
                elapsedLabel.isVisible = false
            }
            ToolState.RUNNING -> {
                startPulsing()
                elapsedLabel.text = formatElapsed(elapsedSeconds)
                elapsedLabel.isVisible = elapsedSeconds > 0
            }
            ToolState.FINISHED -> {
                stopPulsing()
                card.line = ChatTheme.TOOL_FINISHED
                elapsedLabel.isVisible = false
                card.repaint()
            }
        }
    }

    /** Interpolated border colour for the RUNNING pulse — a sine sweep between sky-blue and amber, at global [phase]. */
    private fun pulseColor(phase: Int): Color {
        val t = ((Math.sin(phase * PULSE_SPEED) + 1.0) / 2.0).toFloat()
        return lerp(ChatTheme.TOOL_LOADING, ChatTheme.TOOL_RUNNING, t)
    }

    private fun lerp(a: Color, b: Color, t: Float): Color = Color(
        (a.red + (b.red - a.red) * t).toInt().coerceIn(0, 255),
        (a.green + (b.green - a.green) * t).toInt().coerceIn(0, 255),
        (a.blue + (b.blue - a.blue) * t).toInt().coerceIn(0, 255),
    )

    private fun formatElapsed(seconds: Double): String {
        val s = seconds.toInt()
        return if (s < 60) "${s}s" else "${s / 60}m %02ds".format(s % 60)
    }

    /** Unregister from the shared pulse driver when the row leaves the component tree (transcript rebuild / clear). */
    override fun removeNotify() {
        stopPulsing()
        super.removeNotify()
    }

    private fun iconForTool(name: String?): Icon = ChatTheme.toolIcon(name)

    private companion object {
        /** Radians advanced per frame; ~1.4 s per full sky↔amber↔sky cycle at [PulseDriver.INTERVAL_MS]. */
        const val PULSE_SPEED = 0.18
    }
}

/**
 * A single, process-wide animation clock for the in-flight tool-card pulse. Previously every [ToolRow] owned its
 * own 60 ms [javax.swing.Timer]; with many concurrent tool calls that meant dozens of timers each firing a repaint.
 * Instead, one shared Swing timer advances a global [phase] and repaints only the rows currently in-flight; rows
 * register when they enter LOADING/RUNNING and unregister on FINISHED or [ToolRow.removeNotify] — no per-row timer,
 * no leak. The timer itself only runs while at least one row is registered, and stops when the last one leaves.
 *
 * EDT-confined: Swing timers fire on the EDT and rows register/unregister from EDT callbacks ([ToolRow.setState] /
 * removeNotify), so the row set needs no synchronization.
 */
private object PulseDriver {
    /** Pulse animation frame interval (ms) for in-flight tool cards. */
    const val INTERVAL_MS = 60

    /** Global animation phase, advanced once per frame and read by every registered row so they pulse in sync. */
    @Volatile
    var phase: Int = 0
        private set

    private val rows = java.util.LinkedHashSet<ToolRow>()

    private val timer = javax.swing.Timer(INTERVAL_MS) {
        phase++
        // Snapshot to tolerate a row unregistering itself during its own applyPulse (none does today, but cheap).
        for (row in rows.toList()) row.applyPulse()
    }.apply { isRepeats = true }

    fun register(row: ToolRow) {
        rows.add(row)
        row.applyPulse() // paint the current phase immediately, no wait for the first tick
        if (!timer.isRunning) timer.start()
    }

    fun unregister(row: ToolRow) {
        rows.remove(row)
        if (rows.isEmpty()) timer.stop()
    }
}

private class ToolOutputRow(id: Long) : MessageRow(id) {
    private val area = JTextArea().apply {
        isEditable = false
        lineWrap = false
        font = ChatTheme.mono
        foreground = ChatTheme.TEXT_DIM
        background = ChatTheme.CODE_BG
        border = JBUI.Borders.empty(6, 8)
        margin = Insets(0, 0, 0, 0)
    }
    // Styled pane used only for inline diffs (meta == "diff"): each line is colored by its +/-/@ prefix.
    // getScrollableTracksViewportWidth=false keeps long lines unwrapped (horizontal scroll), matching [area].
    private val diffPane = object : JTextPane() {
        override fun getScrollableTracksViewportWidth() = false
    }.apply {
        isEditable = false
        font = ChatTheme.mono
        background = ChatTheme.CODE_BG
        border = JBUI.Borders.empty(6, 8)
    }
    private val scroll = JBScrollPane(area).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        background = ChatTheme.CODE_BG
        viewport.background = ChatTheme.CODE_BG
    }
    private val card = ChatTheme.RoundedPanel(10, ChatTheme.CODE_BG, ChatTheme.BORDER, shadow = true).apply {
        layout = BorderLayout()
        add(scroll, BorderLayout.CENTER)
    }

    // Reduced top padding so the output block reads as attached to the tool call card above.
    override val baseInsets: Insets get() = Insets(2, 12, 5, 12)

    init {
        add(card, BorderLayout.CENTER)
    }

    override fun update(text: String, meta: String?) {
        if (meta == "diff") renderDiff(text) else renderPlain(text)
    }

    private fun renderPlain(text: String) {
        val lines = text.lines()
        val truncated = if (lines.size > 200) {
            lines.take(200).joinToString("\n") + "\n… (${lines.size - 200} more lines)"
        } else text
        area.text = truncated
        area.caretPosition = 0
        scroll.setViewportView(area)
        // Cap height so a huge file read doesn't flood the chat; let the scrollbar handle the rest.
        capHeight(area.preferredSize.height, JBUIScale.scale(160))
    }

    private fun renderDiff(diff: String) {
        val doc = diffPane.styledDocument
        doc.remove(0, doc.length)
        val lines = diff.lines()
        val shown = if (lines.size > 200) lines.take(200) else lines
        for (line in shown) {
            doc.insertString(doc.length, line + "\n", attrFor(line.firstOrNull()))
        }
        if (lines.size > 200) {
            doc.insertString(doc.length, "… (${lines.size - 200} more lines)\n", attrFor(null))
        }
        diffPane.caretPosition = 0
        scroll.setViewportView(diffPane)
        capHeight(diffPane.preferredSize.height, JBUIScale.scale(240))
    }

    private fun attrFor(prefix: Char?): SimpleAttributeSet = SimpleAttributeSet().apply {
        when (prefix) {
            '+' -> { StyleConstants.setForeground(this, ChatTheme.DIFF_ADDED_FG); StyleConstants.setBackground(this, ChatTheme.DIFF_ADDED_BG) }
            '-' -> { StyleConstants.setForeground(this, ChatTheme.DIFF_REMOVED_FG); StyleConstants.setBackground(this, ChatTheme.DIFF_REMOVED_BG) }
            '@' -> { StyleConstants.setForeground(this, ChatTheme.DIFF_HUNK_FG); StyleConstants.setBold(this, true) }
            else -> StyleConstants.setForeground(this, ChatTheme.TEXT_DIM)
        }
    }

    private fun capHeight(natural: Int, max: Int) {
        scroll.preferredSize = Dimension(0, minOf(natural + JBUIScale.scale(12), max))
        revalidate()
    }
}

private class SystemRow(id: Long) : MessageRow(id) {
    private val content = HtmlContent(dim = true)

    init {
        add(content, BorderLayout.CENTER)
    }

    override fun update(text: String, meta: String?) = content.setMarkdown(text)
}

private class ErrorRow(id: Long) : MessageRow(id) {
    private val content = HtmlContent().apply { foreground = ChatTheme.ERROR }
    private val card = ChatTheme.RoundedPanel(10, ChatTheme.ERROR_BG, ChatTheme.ERROR, shadow = true).apply {
        layout = BorderLayout()
        border = JBUI.Borders.empty(8, 12)
        add(JLabel(AllIcons.General.Error).apply { border = JBUI.Borders.emptyRight(8) }, BorderLayout.WEST)
        add(content, BorderLayout.CENTER)
    }

    init {
        add(card, BorderLayout.CENTER)
    }

    override fun update(text: String, meta: String?) = content.setMarkdown(text)
}

/**
 * A non-editable, word-wrapping HTML view for one message's Markdown. Transparent so the parent bubble/card
 * background shows through; the base font/color come from the component (via HONOR_DISPLAY_PROPERTIES) while
 * the shared stylesheet themes code, links, headings and quotes for dark mode.
 */
class HtmlContent(dim: Boolean = false) : JEditorPane() {
    init {
        editorKit = buildKit()
        isEditable = false
        isOpaque = false
        margin = Insets(0, 0, 0, 0)
        border = JBUI.Borders.empty()
        font = ChatTheme.body
        foreground = if (dim) ChatTheme.TEXT_DIM else ChatTheme.TEXT
        putClientProperty(HONOR_DISPLAY_PROPERTIES, true)
        addHyperlinkListener { e ->
            if (e.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            // Swing exposes non-standard schemes only via getDescription() (e.url is null), so intercept
            // jb://open links there and navigate to the referenced source location; everything else opens
            // in the browser. Any malformed link is a no-op, never an exception.
            val desc = e.description
            if (desc != null && desc.startsWith("jb://open")) {
                navigateToSource(desc)
            } else {
                e.url?.let { BrowserUtil.browse(it) }
            }
        }
    }

    /** Parse a `jb://open?file=<urlenc>&line=<n>` link and open that file at that line in the IDE. */
    private fun navigateToSource(description: String) {
        try {
            val query = description.substringAfter('?', "")
            val params = query.split('&').mapNotNull { p ->
                val k = p.substringBefore('=', "")
                val v = p.substringAfter('=', "")
                if (k.isEmpty()) null else k to v
            }.toMap()
            val rawFile = params["file"] ?: return
            val path = java.net.URLDecoder.decode(rawFile, Charsets.UTF_8)
            val line = (params["line"] ?: "1").toIntOrNull() ?: 1

            val project = com.intellij.ide.DataManager.getInstance().getDataContext(this)
                .getData(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT)
                ?: com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
                ?: return

            val lfs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            val resolved = if (path.startsWith("/")) path else {
                val base = project.basePath ?: return
                "$base/$path"
            }
            // The link text is assistant-controlled: confine navigation to the project tree so a crafted
            // jb://open link (absolute path, ~/.ssh, .. traversal) can't surface out-of-project files in the
            // editor. isWithinRoot canonicalizes both sides (defeats .. and symlinks) and fails closed.
            if (!dev.lain.claudejb.diff.DiffPresenter.isWithinRoot(resolved, project.basePath)) return
            val vFile = lfs.findFileByPath(resolved) ?: return
            com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vFile, (line - 1).coerceAtLeast(0), 0)
                .navigate(true)
        } catch (_: Exception) {
            // Bad link → silently ignore.
        }
    }

    /**
     * Memoizes the last Markdown source rendered into this pane. Streaming replays the same text on the trailing
     * coalesce tick (and a finalize often passes text identical to the last delta), so skipping an unchanged
     * re-render avoids reparsing the whole HTML document for free — the single biggest per-tick cost.
     */
    private var lastMarkdown: String? = null

    /**
     * The theme identity used for the last render. The produced HTML bakes in theme-derived colors (code background,
     * text, syntax-highlight spans — all sourced from [ChatTheme]/the editor scheme), so on a LAF/theme switch the
     * same Markdown must be re-rendered even though [lastMarkdown] is unchanged. We key on the global editor color
     * scheme name (cheap, and exactly what drives those colors); the memo busts when EITHER the text OR theme changed.
     */
    private var lastTheme: String? = null

    private fun themeToken(): String = EditorColorsManager.getInstance().globalScheme.name

    fun setMarkdown(markdown: String) {
        val theme = themeToken()
        if (markdown == lastMarkdown && theme == lastTheme) return
        lastMarkdown = markdown
        lastTheme = theme
        // MarkdownRenderer is a pure, IDE-free converter and drops the fence language, so it can't colorize code.
        // We pre-extract fenced blocks that declare a language, hand the rest (with placeholders) to the renderer,
        // then splice in IDE-lexer-highlighted HTML for each. Blocks with no/unknown language keep the renderer's
        // plain <pre> fallback untouched. NUL-delimited placeholders survive escaping and never occur in prose.
        val blocks = ArrayList<String>()
        val masked = CodeHighlighter.maskHighlightableFences(markdown) { lang, code ->
            blocks.add(CodeHighlighter.highlightToHtml(lang, code))
            blocks.size - 1
        }
        var html = MarkdownRenderer.toHtml(masked)
        if (blocks.isNotEmpty()) {
            // The renderer wraps the placeholder line in <p>…</p>; absorb that wrapper so the <pre> isn't nested
            // inside a paragraph, then catch any stragglers with the bare-placeholder regex.
            html = Regex("<p>\\s*CB(\\d+)\\s*</p>").replace(html) { m -> blocks[m.groupValues[1].toInt()] }
            html = CodeHighlighter.PLACEHOLDER.replace(html) { m -> blocks[m.groupValues[1].toInt()] }
        }
        text = "<html><body>$html</body></html>"
        caretPosition = 0
        revalidate()
    }

    // Don't let the HTML view demand a wide preferred width; it must wrap to whatever the column gives it.
    override fun getMinimumSize(): Dimension = Dimension(JBUIScale.scale(60), super.getMinimumSize().height)

    private fun buildKit(): HTMLEditorKit {
        val ss = StyleSheet().apply {
            addRule("a { color: ${ChatTheme.hex(ChatTheme.ACCENT)}; text-decoration: none; }")
            addRule(
                "code { font-family: 'JetBrains Mono','Menlo',monospace; " +
                    "background-color: ${ChatTheme.hex(ChatTheme.CODE_BG)}; }"
            )
            addRule(
                "pre { font-family: 'JetBrains Mono','Menlo',monospace; " +
                    "background-color: ${ChatTheme.hex(ChatTheme.CODE_BG)}; " +
                    "color: ${ChatTheme.hex(ChatTheme.TEXT)}; margin: 6px 0; padding: 8px; }"
            )
            addRule("h1,h2,h3,h4,h5,h6 { color: ${ChatTheme.hex(ChatTheme.TEXT)}; margin: 8px 0 4px 0; }")
            addRule("blockquote { color: ${ChatTheme.hex(ChatTheme.TEXT_DIM)}; margin: 4px 0 4px 8px; }")
            addRule("ul,ol { margin: 4px 0 4px 6px; }")
            addRule("li { margin: 2px 0; }")
            addRule("p { margin: 4px 0; }")
            // Swing's HTML 3.2 engine honors cell borders/padding as attributes via these rules.
            addRule(
                "table { margin: 6px 0; border: 1px solid ${ChatTheme.hex(ChatTheme.BORDER)}; }"
            )
            addRule(
                "th { border: 1px solid ${ChatTheme.hex(ChatTheme.BORDER)}; padding: 3px 8px; " +
                    "background-color: ${ChatTheme.hex(ChatTheme.CODE_BG)}; " +
                    "color: ${ChatTheme.hex(ChatTheme.TEXT)}; text-align: left; }"
            )
            addRule(
                "td { border: 1px solid ${ChatTheme.hex(ChatTheme.BORDER)}; padding: 3px 8px; " +
                    "color: ${ChatTheme.hex(ChatTheme.TEXT)}; }"
            )
        }
        return HTMLEditorKitBuilder().withWordWrapViewFactory().withStyleSheet(ss).build()
    }
}

/**
 * Native syntax highlighting for fenced code blocks, using the IDE's own lexers (the same machinery the editor
 * uses) so colors track the active editor color scheme on any theme. The Markdown renderer is a pure, IDE-free
 * converter that discards the fence language; this fills that gap by extracting language-tagged fences before
 * rendering, tokenizing each via [SyntaxHighlighter] / its highlighting [com.intellij.lexer.Lexer], mapping each
 * token's [TextAttributesKey] to a scheme color, and emitting a colored `<pre>`. Blocks with no language or an
 * unknown one keep the renderer's plain `<pre>` fallback.
 *
 * Lexers run headless (no editor/document needed), so this is safe off-screen and in tests.
 */
object CodeHighlighter {
    /** NUL-wrapped marker for a pre-rendered code block, spliced back after Markdown rendering. */
    val PLACEHOLDER = Regex(" CB(\\d+) ")

    /** Common fence-language aliases → a representative filename the IDE's FileTypeManager recognizes by extension. */
    private val LANG_TO_FILENAME: Map<String, String> = mapOf(
        "kotlin" to "a.kt", "kt" to "a.kt", "kts" to "a.kts",
        "java" to "a.java",
        "python" to "a.py", "py" to "a.py",
        "javascript" to "a.js", "js" to "a.js", "mjs" to "a.mjs",
        "typescript" to "a.ts", "ts" to "a.ts", "tsx" to "a.tsx", "jsx" to "a.jsx",
        "json" to "a.json", "json5" to "a.json5",
        "yaml" to "a.yaml", "yml" to "a.yaml",
        "xml" to "a.xml", "html" to "a.html", "htm" to "a.html",
        "css" to "a.css", "scss" to "a.scss", "less" to "a.less",
        "sql" to "a.sql",
        "go" to "a.go", "rust" to "a.rs", "rs" to "a.rs",
        "c" to "a.c", "cpp" to "a.cpp", "c++" to "a.cpp", "h" to "a.h", "hpp" to "a.hpp",
        "cs" to "a.cs", "csharp" to "a.cs",
        "ruby" to "a.rb", "rb" to "a.rb",
        "php" to "a.php",
        "swift" to "a.swift",
        "sh" to "a.sh", "bash" to "a.sh", "shell" to "a.sh", "zsh" to "a.sh",
        "properties" to "a.properties",
        "toml" to "a.toml",
        "groovy" to "a.groovy", "gradle" to "a.gradle",
        "markdown" to "a.md", "md" to "a.md",
        "dockerfile" to "Dockerfile",
    )

    /** Maps a fence language token to a filename whose extension the IDE associates with a [FileType], or null. */
    fun filenameForLang(lang: String): String? = LANG_TO_FILENAME[lang.trim().lowercase()]

    /**
     * Replaces every ```` ```lang ```` fenced block that has a *recognized* language with a placeholder, invoking
     * [render] with the language and raw code to obtain the block's index. Untagged or unknown-language fences are
     * left verbatim for the Markdown renderer's plain `<pre>` fallback. An unterminated fence (streaming) runs to
     * the end and is left to the renderer, so half-typed code never flashes mis-highlighted.
     */
    fun maskHighlightableFences(markdown: String, render: (lang: String, code: String) -> Int): String {
        val lines = markdown.replace("\r\n", "\n").split("\n")
        val out = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            val fence = Regex("^```\\s*([\\w+#.\\-]+)\\s*$").find(trimmed)
            if (fence != null && filenameForLang(fence.groupValues[1]) != null) {
                val lang = fence.groupValues[1]
                val code = StringBuilder()
                var j = i + 1
                var closed = false
                while (j < lines.size) {
                    if (lines[j].trim() == "```") { closed = true; break }
                    code.append(lines[j]).append('\n')
                    j++
                }
                if (closed) {
                    val idx = render(lang, code.toString().trimEnd('\n'))
                    // Stand-alone line so the renderer emits it as its own paragraph, easy to splice back.
                    out.append("\n CB").append(idx).append(" \n")
                    i = j + 1
                    continue
                }
                // Unterminated → fall through, let the renderer stream it as a plain fence.
            }
            out.append(line)
            if (i < lines.size - 1) out.append('\n')
            i++
        }
        return out.toString()
    }

    /**
     * Tokenizes [code] with the IDE lexer for [lang] and renders a colored `<pre>`. Falls back to a plain escaped
     * `<pre>` if no highlighter/lexer is available. Pure-JVM safe: no editor or PSI is created.
     */
    fun highlightToHtml(lang: String, code: String): String {
        val highlighter = highlighterFor(lang)
            ?: return "<pre style=\"${preStyle()}\">${escapeHtml(code)}</pre>"
        val scheme = EditorColorsManager.getInstance().globalScheme
        val lexer = highlighter.highlightingLexer
        val sb = StringBuilder("<pre style=\"${preStyle()}\">")
        try {
            lexer.start(code)
            while (lexer.tokenType != null) {
                val text = code.substring(lexer.tokenStart, lexer.tokenEnd)
                val keys: List<TextAttributesKey> = highlighter.getTokenHighlights(lexer.tokenType).toList()
                // Most specific key wins: highlighters list from generic → specific, so scan in reverse.
                val color: java.awt.Color? = keys.reversed()
                    .firstNotNullOfOrNull { scheme.getAttributes(it)?.foregroundColor }
                val escaped = escapeHtml(text)
                if (color != null) {
                    sb.append("<span style=\"color:").append(ChatTheme.hex(color)).append("\">")
                        .append(escaped).append("</span>")
                } else {
                    sb.append(escaped)
                }
                lexer.advance()
            }
        } catch (_: Exception) {
            // Any lexer hiccup → plain, never break the message.
            return "<pre style=\"${preStyle()}\">${escapeHtml(code)}</pre>"
        }
        sb.append("</pre>")
        return sb.toString()
    }

    /**
     * Per-language [SyntaxHighlighter] cache. Resolving a highlighter (FileType lookup + factory) is non-trivial
     * and was repeated for every code block on every (streaming) render; the highlighter is stateless and reusable
     * across blocks — its [com.intellij.lexer.Lexer] is obtained fresh per call in [highlightToHtml] — so we memoize
     * by the canonical lowercased language token. A null result (no highlighter) is cached too, to skip re-lookups.
     */
    private val highlighterCache = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<SyntaxHighlighter>>()

    private fun highlighterFor(lang: String): SyntaxHighlighter? {
        val key = lang.trim().lowercase()
        highlighterCache[key]?.let { return it.orElse(null) }
        val filename = filenameForLang(lang)
        val resolved = if (filename == null) null else {
            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(filename)
            SyntaxHighlighterFactory.getSyntaxHighlighter(fileType, null, null)
        }
        highlighterCache[key] = java.util.Optional.ofNullable(resolved)
        return resolved
    }

    private fun preStyle(): String =
        "font-family:'JetBrains Mono','Menlo',monospace; " +
            "background-color:${ChatTheme.hex(ChatTheme.CODE_BG)}; margin:6px 0; padding:8px;"

    /** Escapes for HTML and preserves leading-space indentation that Swing's HTML would otherwise collapse. */
    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/**
 * A small icon action button for in-card actions (Copy / View diff / Revert). Unlike a bare [JLabel], it reserves a
 * comfortable padded hit area (so a 16px glyph isn't a 16px target) and paints a subtle rounded hover highlight, so
 * the buttons are easy to aim at and clearly clickable — even when several sit side by side in a tool card.
 */
private fun iconButton(icon: Icon, tooltip: String, onClick: () -> Unit): JComponent =
    object : JLabel(icon) {
        var hovered = false
        override fun paintComponent(g: java.awt.Graphics) {
            if (hovered) {
                val g2 = g.create() as java.awt.Graphics2D
                try {
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = Color(128, 128, 128, 45)
                    val a = JBUIScale.scale(6)
                    g2.fillRoundRect(0, 0, width, height, a, a)
                } finally {
                    g2.dispose()
                }
            }
            super.paintComponent(g)
        }
    }.apply {
        toolTipText = tooltip
        horizontalAlignment = SwingConstants.CENTER
        // A padded hit area: a 16px glyph becomes a ~26px clickable target, comfortably separated from its neighbour.
        border = JBUI.Borders.empty(JBUIScale.scale(3), JBUIScale.scale(5))
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onClick()
            override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hovered = false; repaint() }
        })
    }
