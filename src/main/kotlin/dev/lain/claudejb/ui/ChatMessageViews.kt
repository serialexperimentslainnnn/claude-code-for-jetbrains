package dev.lain.claudejb.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import dev.lain.claudejb.session.Speaker
import dev.lain.claudejb.session.TranscriptEntry
import java.awt.BorderLayout
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
        val block = ChatTheme.RoundedPanel(8, ChatTheme.USER_BUBBLE).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(8, 12)
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
            add(JLabel(ChatTheme.avatar))
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
    private val card = ChatTheme.RoundedPanel(10, ChatTheme.CARD_BG, ChatTheme.BORDER).apply {
        layout = BorderLayout()
        border = JBUI.Borders.empty(6, 10)
        add(toggle, BorderLayout.WEST)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(icon, BorderLayout.WEST)
            add(label, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
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
    }

    private fun iconForTool(name: String?): Icon = when (name) {
        "Bash" -> AllIcons.Debugger.Console
        "Read" -> AllIcons.Actions.MenuOpen
        "Edit", "Write", "MultiEdit", "NotebookEdit" -> AllIcons.Actions.Edit
        "Grep", "Glob" -> AllIcons.Actions.Find
        "WebFetch", "WebSearch" -> AllIcons.General.Web
        "Task" -> AllIcons.Actions.RunAll
        else -> AllIcons.Nodes.Plugin
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
    private val card = ChatTheme.RoundedPanel(10, ChatTheme.CODE_BG, ChatTheme.BORDER).apply {
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
    private val card = ChatTheme.RoundedPanel(10, ChatTheme.ERROR_BG, ChatTheme.ERROR).apply {
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

    fun setMarkdown(markdown: String) {
        text = "<html><body>${MarkdownRenderer.toHtml(markdown)}</body></html>"
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

/** A tiny borderless icon button used for in-card actions (e.g. copy). */
private fun iconButton(icon: Icon, tooltip: String, onClick: () -> Unit): JComponent =
    JLabel(icon).apply {
        toolTipText = tooltip
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onClick()
        })
    }
