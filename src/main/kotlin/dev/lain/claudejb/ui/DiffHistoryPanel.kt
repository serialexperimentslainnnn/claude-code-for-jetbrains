package dev.lain.claudejb.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import dev.lain.claudejb.diff.DiffPresenter
import dev.lain.claudejb.diff.EditSnapshot
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.ReviewableEdit
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * A tool-window tab listing every reviewable Edit/Write/MultiEdit made in the active session, newest concerns
 * surfaced as cards (one per [ReviewableEdit]): the root-relative path, the tool, a `+a/-b` line
 * summary reconstructed natively from [DiffPresenter.unifiedDiff], a **View diff** affordance (the in-editor
 * native diff) and a per-edit **Revert** that restores the captured pre-write text via [ClaudeSession.revertEdit].
 *
 * The header also offers a single **Roll back all changes** that reverts the whole session in one confirmed step.
 *
 * EDT-confined: every `session.revertEdit`/`revertAllEdits` call happens on the EDT (this panel lives there), and
 * [refresh] rebuilds the list from [ClaudeSession.reviewableEdits] so it reflects edits made since it was opened.
 */
class DiffHistoryPanel(private val project: Project, private val session: ClaudeSession) : JPanel(BorderLayout()) {

    /** The session this tab reflects, so the factory can find an existing tab instead of opening a duplicate. */
    val boundSession: ClaudeSession get() = session

    private val list = JPanel(VerticalLayout(JBUIScale.scale(6))).apply {
        isOpaque = false
        border = JBUI.Borders.empty(8)
    }

    init {
        isOpaque = true
        background = ChatTheme.BG
        add(header(), BorderLayout.NORTH)
        add(JBScrollPane(list).apply {
            border = BorderFactory.createEmptyBorder()
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }, BorderLayout.CENTER)
        refresh()
    }

    /** Rebuilds the card list from the session's current reviewable edits (oldest-first) and repaints. */
    fun refresh() {
        list.removeAll()
        val edits = session.reviewableEdits()
        if (edits.isEmpty()) {
            list.add(JBLabel("No edits yet in this session.").apply {
                foreground = ChatTheme.TEXT_DIM
                font = ChatTheme.small
                horizontalAlignment = SwingConstants.LEFT
            })
        } else {
            edits.forEach { list.add(row(it)) }
        }
        list.revalidate()
        list.repaint()
    }

    private fun header(): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(8, 8, 4, 8)
        add(JBLabel("Diff History").apply {
            foreground = ChatTheme.TEXT
            font = ChatTheme.body.deriveFont(Font.BOLD)
        }, BorderLayout.WEST)
        add(button("Roll back all changes", ChatTheme.ERROR) { rollbackAll() }, BorderLayout.EAST)
    }

    private fun rollbackAll() {
        if (session.reviewableEdits().isEmpty()) return
        val ok = Messages.showYesNoDialog(
            project,
            "Revert every Edit/Write Claude made in this session?\nEach file is restored to its captured pre-write state.",
            "Roll Back All Changes", "Revert All", "Cancel", Messages.getWarningIcon(),
        )
        if (ok != Messages.YES) return
        val reverted = session.revertAllEdits()
        refresh()
        Messages.showInfoMessage(
            project,
            if (reverted == 1) "Reverted 1 change." else "Reverted $reverted changes.",
            "Roll Back All Changes",
        )
    }

    private fun row(edit: ReviewableEdit): JPanel {
        val snapshot = edit.snapshot
        return ChatTheme.RoundedPanel(10, ChatTheme.CARD_BG, ChatTheme.BORDER, shadow = true).apply {
            layout = BorderLayout(JBUIScale.scale(8), 0)
            border = JBUI.Borders.empty(8, 10, 10, 8)

            add(JPanel(VerticalLayout(JBUIScale.scale(2))).apply {
                isOpaque = false
                add(JBLabel(edit.displayPath).apply {
                    foreground = ChatTheme.TEXT
                    font = ChatTheme.body.deriveFont(Font.BOLD)
                })
                add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                    isOpaque = false
                    add(JBLabel(snapshot.toolName).apply {
                        foreground = ChatTheme.TEXT_DIM
                        font = ChatTheme.small
                    })
                    addDiffStat(this, snapshot)
                })
            }, BorderLayout.CENTER)

            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(button("View diff", ChatTheme.ACCENT) { viewDiff(snapshot) })
                add(button("Revert", ChatTheme.TEXT) { revert(snapshot) })
            }, BorderLayout.EAST)
        }
    }

    /** Appends green `+a` and red `-b` labels, counted from the native unified diff of this edit. */
    private fun addDiffStat(host: JPanel, snapshot: EditSnapshot) {
        val current = snapshot.beforeText
        val proposed = DiffPresenter.proposedContent(snapshot.toolName, snapshot.input, current) ?: current
        var added = 0
        var removed = 0
        for (line in DiffPresenter.unifiedDiff(current, proposed).split("\n")) {
            when {
                line.startsWith("+++") || line.startsWith("---") -> {}
                line.startsWith("+") -> added++
                line.startsWith("-") -> removed++
            }
        }
        host.add(JBLabel("+$added").apply {
            foreground = ChatTheme.DIFF_ADDED_FG
            font = ChatTheme.small
        })
        host.add(JBLabel("-$removed").apply {
            foreground = ChatTheme.DIFF_REMOVED_FG
            font = ChatTheme.small
        })
    }

    private fun viewDiff(snapshot: EditSnapshot) {
        DiffPresenter.openDiff(project, snapshot.toolName, snapshot.input, snapshot.beforeText)
    }

    private fun revert(snapshot: EditSnapshot) {
        if (session.revertEdit(snapshot)) refresh()
        else Messages.showErrorDialog(project, "Could not revert this change.", "Revert Change")
    }

    private fun button(text: String, fg: java.awt.Color, onClick: () -> Unit): JButton = JButton(text).apply {
        isFocusable = false
        isContentAreaFilled = false
        isBorderPainted = false
        isOpaque = false
        foreground = fg
        font = ChatTheme.small
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        margin = JBUI.insets(2, 8)
        addActionListener { onClick() }
    }
}
