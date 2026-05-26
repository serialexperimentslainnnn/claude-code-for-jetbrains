package dev.lain.claudejb.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.HTMLEditorKitBuilder
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
import javax.swing.SwingConstants
import javax.swing.event.HyperlinkEvent
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
    init {
        isOpaque = false
        border = JBUI.Borders.empty(5, 12)
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

private class ToolRow(id: Long) : MessageRow(id) {
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
    private val card = ChatTheme.RoundedPanel(10, ChatTheme.CARD_BG, ChatTheme.BORDER).apply {
        layout = BorderLayout()
        border = JBUI.Borders.empty(6, 10)
        add(icon, BorderLayout.WEST)
        add(label, BorderLayout.CENTER)
    }

    init {
        add(card, BorderLayout.CENTER)
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
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) e.url?.let { BrowserUtil.browse(it) }
        }
    }

    fun setMarkdown(markdown: String) {
        text = "<html><body>${MarkdownRenderer.toHtml(markdown)}</body></html>"
        caretPosition = 0
        revalidate()
    }

    // Don't let the HTML view demand a wide preferred width; it must wrap to whatever the column gives it.
    override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(60), super.getMinimumSize().height)

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
