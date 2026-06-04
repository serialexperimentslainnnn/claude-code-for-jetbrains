package dev.lain.claudejb.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import dev.lain.claudejb.session.Speaker
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

    // Tool rows by their tool_use id, so an output or nested entry can be routed *into* its tool item's
    // children container rather than scattered as a flat sibling.
    private val toolRowByToolUseId = HashMap<String, ToolRow>()

    /** Wired by [ChatPanel]: opens the persisted old↔new diff for a reviewable tool call by its tool_use id.
     *  Kept as a callback so this view stays decoupled from ClaudeSession/DiffPresenter. */
    var onViewDiff: ((toolUseId: String) -> Unit)? = null

    /** Wired by [ChatPanel]: reverts a reviewable tool call's write (by its tool_use id) from its pre-write snapshot. */
    var onRevert: ((toolUseId: String) -> Unit)? = null

    /**
     * Streaming coalescing: re-rendering a row's Markdown means reparsing its whole HTML document, which is
     * O(n) per delta and O(n²) over a long streamed message — enough to choke the EDT. Instead of rendering
     * on every [onUpdated], we mark the entry dirty and flush at most ~12×/s. The timer always renders the
     * entry's *current* text, so the final delta (and the finalize replaceText) is captured by the trailing tick.
     */
    private val dirty = LinkedHashMap<Long, TranscriptEntry>()
    private val flushTimer = javax.swing.Timer(80) { flushDirty() }.apply { isRepeats = true }

    /** Tracks the viewport width so HTML rows wrap instead of forcing a horizontal scrollbar. */
    private val content = object : JPanel(VerticalLayout(JBUIScale.scale(2))), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int) = JBUIScale.scale(24)
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
        verticalScrollBar.unitIncrement = JBUIScale.scale(24)
        viewport.background = ChatTheme.BG
    }

    private val emptyState = buildEmptyState()

    // Whether the view should track the bottom. Stays true while the user reads the latest content; goes false
    // once they scroll up. Used to re-pin to the bottom when the viewport shrinks (e.g. a permission / question
    // card appears in the composer below), which otherwise would hide the last message above the fold.
    private var stickToBottom = true
    private var lastVerticalMax = 0

    /**
     * Force-follow: when on (default), the view re-pins to the bottom on every new delta **even if the user
     * scrolled up** — so a streaming answer always stays in view. The composer's follow toggle flips this. When
     * off, only the natural sticky behaviour applies: it follows while you're at the bottom and leaves you alone
     * once you scroll up to read history. Either way, content at the very bottom always follows. Enabling it
     * snaps to the bottom immediately.
     */
    var followOutput: Boolean = true
        set(value) {
            field = value
            if (value) scrollToBottom()
        }

    /** True when new content should auto-scroll: forced follow, or the user is already parked at the bottom. */
    private fun shouldFollow(atBottom: Boolean) = followOutput || atBottom

    init {
        background = ChatTheme.BG
        add(scroll, BorderLayout.CENTER)
        showEmptyState(true)
        // One listener distinguishes the two causes of a scrollbar change: a pure scroll (max unchanged) updates
        // stickiness from the user's position; a content/viewport change (max changed — new message, or a tray
        // growing below) keeps the prior stickiness and re-pins to the bottom when we were following it.
        scroll.verticalScrollBar.addAdjustmentListener {
            val bar = scroll.verticalScrollBar
            if (bar.maximum == lastVerticalMax) {
                stickToBottom = bar.value + bar.visibleAmount >= bar.maximum - JBUIScale.scale(24)
            } else {
                lastVerticalMax = bar.maximum
                if (stickToBottom) SwingUtilities.invokeLater { bar.value = bar.maximum }
            }
        }
    }

    override fun onAdded(entry: TranscriptEntry, index: Int) {
        val atBottom = isAtBottom()
        showEmptyState(false)
        val row = MessageRow.create(entry)
        rows[entry.id] = row

        if (entry.speaker == Speaker.TOOL && entry.toolUseId != null) {
            (row as ToolRow).onToggle = {
                content.revalidate()
                content.repaint()
                if (atBottom) scrollToBottom()
            }
            // Wire the "View diff" button (shown only for reviewable Edit/Write/MultiEdit rows): delegate to the
            // callback ChatPanel provides, which reopens the snapshot diff. Order-independent (both setters refresh).
            row.toolUseId = entry.toolUseId
            row.onViewDiff = onViewDiff
            row.onRevert = onRevert
            row.setState(entry.toolState, entry.elapsedSeconds)
            toolRowByToolUseId[entry.toolUseId] = row
        }

        // Route the row into its tool item's children container when it belongs to one (an output to its own
        // call, nested activity to its Agent); each parent's direct children arrive in order, so append keeps
        // them ordered without touching the flat list. Top-level entries append to the flat transcript.
        val parentRow = directParentToolId(entry)?.let { toolRowByToolUseId[it] }
        if (parentRow != null) parentRow.addChild(row) else content.add(row)

        content.revalidate()
        content.repaint()
        if (shouldFollow(atBottom)) scrollToBottom()
    }

    /** The tool whose item this entry lives inside: its own call (for an output) or its Agent (nested activity). */
    private fun directParentToolId(e: TranscriptEntry): String? =
        if (e.speaker == Speaker.TOOL_OUTPUT) e.toolUseId else e.parentToolUseId

    override fun onUpdated(entry: TranscriptEntry) {
        if (entry.id !in rows) return
        // Coalesce: defer the heavy Markdown re-render to the next timer tick instead of running it per delta.
        dirty[entry.id] = entry
        if (!flushTimer.isRunning) flushTimer.start()
    }

    /**
     * Renders every row that changed since the last tick. For a long transcript, calling [content].revalidate()
     * on every ~12 Hz tick re-lays-out the whole VerticalLayout (O(n) rows) just to grow the one streaming row —
     * O(n²) over a message. Instead we measure each updated row's preferred height before/after [update]: when it
     * is unchanged (the common case for a delta that fits the current line span) we revalidate/repaint only that
     * row, leaving the parent layout untouched; only a height change (a new wrapped line, a finished tool box)
     * triggers the full [content].revalidate() so siblings reflow. Scroll-pinning is unchanged.
     */
    private fun flushDirty() {
        if (dirty.isEmpty()) {
            flushTimer.stop()
            return
        }
        val atBottom = isAtBottom()
        val batch = dirty.values.toList()
        dirty.clear()
        var layoutChanged = false
        for (entry in batch) {
            val row = rows[entry.id] ?: continue
            val before = row.preferredSize.height
            row.update(entry.text, entry.meta)
            if (entry.speaker == Speaker.TOOL) (row as? ToolRow)?.setState(entry.toolState, entry.elapsedSeconds)
            val after = row.preferredSize.height
            if (after != before) {
                layoutChanged = true
            } else {
                // Height stable: repaint just this row, skip the whole-transcript re-layout.
                row.revalidate()
                row.repaint()
            }
        }
        // Only reflow the stack when a row's footprint actually changed.
        if (layoutChanged) content.revalidate()
        // Keep following the stream whenever we were pinned (or force-follow is on): scrollToBottom is a cheap
        // viewport adjustment and runs (via invokeLater) after any row revalidate, so the
        // newest line stays visible even when the height-change heuristic reports a stale
        // preferredSize (JEditorPane not yet refreshed after setText).
        if (shouldFollow(atBottom)) scrollToBottom()
    }

    override fun onCleared() {
        flushTimer.stop()
        dirty.clear()
        rows.clear()
        toolRowByToolUseId.clear()
        content.removeAll()
        showEmptyState(true)
        content.revalidate()
        content.repaint()
    }

    /** Re-lays the whole transcript — used when a child's footprint changes outside a normal update, e.g. Vibe Mode
     *  swapping the avatar glyph for the (differently-sized) neon helmet. */
    fun relayout() {
        content.revalidate()
        content.repaint()
    }

    /** Whether reasoning ("Thought process") blocks are currently expanded. Toggled by Ctrl+O. */
    private var reasoningVisible = true

    /**
     * Show/hide every reasoning block at once (Ctrl+O), like the CLI's reasoning toggle. Collapsing shrinks the
     * content above the fold, so the viewport's scroll value would otherwise point at different content and the
     * view "jumps". We capture whether we were following the bottom *before* the relayout and re-pin afterwards
     * (deferred to after the VerticalLayout has applied), keeping the latest message in view.
     */
    fun toggleReasoning() {
        val follow = shouldFollow(isAtBottom())
        reasoningVisible = !reasoningVisible
        rows.values.forEach { it.setReasoningVisible(reasoningVisible) }
        content.revalidate()
        content.repaint()
        if (follow) scrollToBottom()
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
        return bar.value + bar.visibleAmount >= bar.maximum - JBUIScale.scale(24)
    }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            // Force any pending relayout to apply before reading the scrollbar's extent, so we pin to the *new*
            // bottom (after a collapse/expand or a streamed line) rather than a stale maximum.
            scroll.validate()
            val bar = scroll.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    private fun buildEmptyState(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(48, 0, 0, 0)
        add(ChatTheme.avatarLabel().apply { alignmentX = CENTER_ALIGNMENT })
        add(JBLabel("How can I help you today?").apply {
            alignmentX = CENTER_ALIGNMENT
            horizontalAlignment = SwingConstants.CENTER
            foreground = ChatTheme.TEXT_DIM
            font = ChatTheme.body
            border = JBUI.Borders.emptyTop(10)
        })
    }
}
