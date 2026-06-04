package dev.lain.claudejb.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * The "in-flight queue" strip shown just above the composer: one `⏳ prompt ✕` row per prompt the user
 * has queued while a turn is active. Extracted verbatim (look-wise) from `ChatPanel` so the composer no
 * longer owns this concern.
 *
 * Autonomous: it knows nothing about the session. [update] repaints the rows from a plain `List<String>`;
 * clicking a row's ✕ invokes [onRemove] with that row's index, which the host maps to its own removal.
 *
 * The panel hides itself when the list is empty (so it adds no vertical gap to the composer stack).
 */
class QueueStripPanel(
    private val onRemove: (index: Int) -> Unit,
) : JPanel() {

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.emptyBottom(4)
        isVisible = false
    }

    /** Repaints the strip from the queued prompts (most-recent-first ordering is the caller's choice). */
    fun update(prompts: List<String>) {
        removeAll()
        isVisible = prompts.isNotEmpty()
        prompts.forEachIndexed { index, prompt -> add(queueRow(index, prompt)) }
        revalidate()
        repaint()
    }

    private fun queueRow(index: Int, prompt: String): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(JBLabel("⏳").apply { foreground = ChatTheme.TEXT_DIM })
            add(JBLabel(truncate(prompt, QUEUE_PROMPT_MAX_CHARS)).apply { foreground = ChatTheme.TEXT_DIM })
            add(linkButton("✕") { onRemove(index) })
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
        /** Max characters of a queued prompt shown in the queue strip before truncation. */
        private const val QUEUE_PROMPT_MAX_CHARS = 70
    }
}
