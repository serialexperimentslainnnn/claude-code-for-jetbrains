package dev.lain.claudejb.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.ui.scale.JBUIScale
import java.awt.Cursor
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * The "suggested next prompt" strip shown just above the composer input: a single `💡 suggestion ✕` pill the
 * binary predicts (prompt_suggestion). Clicking the text fills the input (the user reviews/edits — never
 * auto-sent); ✕ dismisses it. Hides itself when there's no suggestion, so it adds no vertical gap.
 *
 * Autonomous: it knows nothing about the session — [onFill]/[onDismiss] route the actions to the host.
 */
class SuggestionStripPanel(
    private val onFill: (String) -> Unit,
    private val onDismiss: () -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEFT, JBUIScale.scale(4), 0)) {

    init {
        isOpaque = false
        border = JBUI.Borders.emptyBottom(4)
        isVisible = false
    }

    /** Repaints the strip; a null/blank [suggestion] hides it. */
    fun update(suggestion: String?) {
        removeAll()
        val text = suggestion?.takeIf { it.isNotBlank() }
        isVisible = text != null
        if (text != null) {
            add(JBLabel("💡").apply { foreground = ChatTheme.TEXT_DIM })
            add(linkButton(truncate(text, SUGGESTION_MAX_CHARS)) { onFill(text) }
                .apply { toolTipText = "Use this as your next prompt" })
            add(linkButton("✕") { onDismiss() })
        }
        revalidate()
        repaint()
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

    private fun truncate(s: String, max: Int): String =
        s.replace('\n', ' ').let { if (it.length > max) it.take(max) + "…" else it }

    companion object {
        private const val SUGGESTION_MAX_CHARS = 90
    }
}
