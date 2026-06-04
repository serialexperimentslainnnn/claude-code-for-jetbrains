package dev.lain.claudejb.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import dev.lain.claudejb.context.Attachment
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * The "pending attachments" strip shown just above the composer: one removable chip per [Attachment] the user
 * has pinned to the next turn (an `@file`, a code selection, or an image). It is the composer's attachment
 * sink — the editor actions ("Add File/Selection as @-context") and drag&drop/paste of images push here, and the
 * chips travel with the prompt when the user sends.
 *
 * Each chip renders a **real** IDE icon (the file-type icon for a [Attachment.FileRef], theme glyphs for
 * selections/images) next to a truncated name, recolours on hover (`CARD_BG → CARD_HOVER`), always shows its
 * remove (✕) control, and opens the attachment in the editor when its body is clicked.
 *
 * Autonomous: it owns the live list and knows nothing about the session. [pin] appends a chip, [items] returns the
 * current attachments (for the host to send), [clear] empties it (after a send), and clicking a chip's ✕ removes
 * just that one. Clicking a chip's body invokes [onOpen] so the host can open the attachment in the editor — the
 * panel stays session-agnostic. The panel hides itself when empty so it adds no vertical gap to the composer stack.
 *
 * @param onOpen host-supplied callback invoked when the user clicks a chip body (not its ✕), to open the attachment.
 *               Defaults to a no-op so the panel compiles/renders standalone.
 */
class AttachmentStripPanel(private val onOpen: (Attachment) -> Unit = {}) : JPanel() {

    private val attachments = mutableListOf<Attachment>()

    init {
        layout = FlowLayout(FlowLayout.LEFT, JBUIScale.scale(4), JBUIScale.scale(2))
        isOpaque = false
        border = JBUI.Borders.emptyBottom(4)
        isVisible = false
    }

    /** Current pinned attachments, in insertion order. */
    fun items(): List<Attachment> = attachments.toList()

    /** Pins [attachment] and repaints. Duplicate file/selection refs (same display name + kind) are ignored. */
    fun pin(attachment: Attachment) {
        if (attachments.any { it.displayName == attachment.displayName && it::class == attachment::class }) return
        attachments += attachment
        rebuild()
    }

    /** Clears all pinned attachments (call after a successful send) and repaints. */
    fun clear() {
        if (attachments.isEmpty()) return
        attachments.clear()
        rebuild()
    }

    private fun rebuild() {
        removeAll()
        isVisible = attachments.isNotEmpty()
        attachments.toList().forEach { add(chip(it)) }
        revalidate()
        repaint()
    }

    private fun chip(attachment: Attachment): JPanel {
        val panel = ChatTheme.RoundedPanel(arc = 6, fill = ChatTheme.CARD_BG, line = ChatTheme.BORDER)
        panel.layout = FlowLayout(FlowLayout.LEFT, JBUIScale.scale(3), 0)
        panel.border = JBUI.Borders.empty(0, 5, 0, 3)
        panel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        val label = JBLabel(
            truncate(attachment.displayName, CHIP_MAX_CHARS),
            scaledIcon(iconFor(attachment)),
            SwingConstants.LEFT,
        ).apply {
            foreground = ChatTheme.TEXT
            font = ChatTheme.small
            iconTextGap = JBUIScale.scale(3)
            toolTipText = attachment.displayName
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        val remove = removeAffordance(attachment)

        // Hover only recolours the chip — the ✕ is ALWAYS visible (revealing it on hover made the chip resize and,
        // worse, moving the cursor onto the ✕ child fired mouseExited on the chip, hiding the ✕ before it could be
        // clicked). mouseExited fires when the cursor enters a child too, so only revert the highlight once the
        // cursor has truly left the whole chip (getMousePosition(true) includes children).
        val hover = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                panel.fill = ChatTheme.CARD_HOVER
            }

            override fun mouseExited(e: MouseEvent) {
                if (panel.getMousePosition(true) == null) panel.fill = ChatTheme.CARD_BG
            }

            // Clicking the chip body opens the attachment; the ✕ is its own button and never reaches this listener.
            override fun mouseClicked(e: MouseEvent) = onOpen(attachment)
        }
        panel.addMouseListener(hover)
        label.addMouseListener(hover)

        panel.add(label)
        panel.add(remove)
        return panel
    }

    /**
     * The ✕ remove control: a compact, self-painted glyph (~11px) — smaller and tighter than the stock
     * [AllIcons.Actions.Close] (16px), so the chip reads as a small token rather than a chunky button. It is a
     * real [JButton] (not a bare component): focusable and keyboard-activatable (Space/Enter) with a proper push-
     * button accessible role, so screen-reader / keyboard-only users can still remove an attachment. Highlights on
     * hover **or keyboard focus**, and drops just this attachment when activated.
     */
    private fun removeAffordance(attachment: Attachment): JComponent = object : JButton() {
        private val side = JBUIScale.scale(14)

        init {
            preferredSize = Dimension(side, side)
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            border = JBUI.Borders.empty()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Remove attachment"
            addActionListener {
                attachments.remove(attachment)
                rebuild()
            }
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val w = width; val h = height
                val active = model.isRollover || model.isPressed || isFocusOwner
                if (active) {
                    g2.color = Color(128, 128, 128, 50)
                    g2.fillRoundRect(0, 0, w, h, JBUIScale.scale(4), JBUIScale.scale(4))
                }
                val pad = JBUIScale.scale(4)
                g2.color = if (active) ChatTheme.TEXT else ChatTheme.TEXT_DIM
                g2.stroke = BasicStroke(JBUIScale.scale(1.3f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(pad, pad, w - pad, h - pad)
                g2.drawLine(w - pad, pad, pad, h - pad)
            } finally {
                g2.dispose()
            }
        }
    }

    private fun iconFor(a: Attachment): Icon = when (a) {
        is Attachment.FileRef ->
            LocalFileSystem.getInstance().findFileByPath(a.path)?.let { IconUtil.getIcon(it, 0, null) }
                ?: AllIcons.FileTypes.Any_type
        is Attachment.Selection -> ChatTheme.iconSelection
        is Attachment.Image -> ChatTheme.iconImage
    }

    /** Scales a 16px icon down to a compact ~13px so the chip stays small. */
    private fun scaledIcon(icon: Icon): Icon =
        IconUtil.scale(icon, null, JBUIScale.scale(13f) / icon.iconWidth.coerceAtLeast(1))

    private fun truncate(s: String, max: Int): String =
        s.replace('\n', ' ').let { if (it.length > max) it.take(max) + "…" else it }

    companion object {
        private const val CHIP_MAX_CHARS = 40
    }
}
