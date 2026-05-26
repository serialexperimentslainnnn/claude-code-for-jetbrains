package dev.lain.claudejb.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import dev.lain.claudejb.session.TranscriptEntry
import dev.lain.claudejb.session.TranscriptModel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * The scrolling conversation: a vertical stack of [MessageRow] cards over the dark chat surface, fed by a
 * [TranscriptModel]. Rows are kept by entry id so streaming deltas update the right card in place. Scrolling
 * is "sticky" — it follows new content only while the user is already at the bottom, so reading history
 * mid-stream isn't interrupted.
 */
class TranscriptView : JPanel(BorderLayout()), TranscriptModel.Listener {

    private val rows = LinkedHashMap<Long, MessageRow>()

    /** Tracks the viewport width so HTML rows wrap instead of forcing a horizontal scrollbar. */
    private val content = object : JPanel(VerticalLayout(JBUI.scale(2))), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int) = JBUI.scale(24)
        override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int) = r.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }.apply {
        isOpaque = true
        background = ChatTheme.BG
        border = JBUI.Borders.empty(8, 4)
    }

    private val scroll = JBScrollPane(content).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBar.unitIncrement = JBUI.scale(24)
        viewport.background = ChatTheme.BG
    }

    private val emptyState = buildEmptyState()

    init {
        background = ChatTheme.BG
        add(scroll, BorderLayout.CENTER)
        showEmptyState(true)
    }

    override fun onAdded(entry: TranscriptEntry) {
        val atBottom = isAtBottom()
        showEmptyState(false)
        val row = MessageRow.create(entry)
        rows[entry.id] = row
        content.add(row)
        content.revalidate()
        content.repaint()
        if (atBottom) scrollToBottom()
    }

    override fun onUpdated(entry: TranscriptEntry) {
        val row = rows[entry.id] ?: return
        val atBottom = isAtBottom()
        row.update(entry.text, entry.meta)
        content.revalidate()
        if (atBottom) scrollToBottom()
    }

    override fun onCleared() {
        rows.clear()
        content.removeAll()
        showEmptyState(true)
        content.revalidate()
        content.repaint()
    }

    /** Whether reasoning ("Thought process") blocks are currently expanded. Toggled by Ctrl+O. */
    private var reasoningVisible = true

    /** Show/hide every reasoning block at once (Ctrl+O), like the CLI's reasoning toggle. */
    fun toggleReasoning() {
        reasoningVisible = !reasoningVisible
        rows.values.forEach { it.setReasoningVisible(reasoningVisible) }
        content.revalidate()
        content.repaint()
    }

    private fun showEmptyState(show: Boolean) {
        if (show && emptyState.parent == null) {
            content.add(emptyState)
        } else if (!show && emptyState.parent != null) {
            content.remove(emptyState)
        }
    }

    private fun isAtBottom(): Boolean {
        val bar = scroll.verticalScrollBar
        return bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(24)
    }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            val bar = scroll.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    private fun buildEmptyState(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(48, 0, 0, 0)
        add(JBLabel(ChatTheme.avatar).apply { alignmentX = CENTER_ALIGNMENT })
        add(JBLabel("How can I help you today?").apply {
            alignmentX = CENTER_ALIGNMENT
            horizontalAlignment = SwingConstants.CENTER
            foreground = ChatTheme.TEXT_DIM
            font = ChatTheme.body
            border = JBUI.Borders.emptyTop(10)
        })
    }
}
