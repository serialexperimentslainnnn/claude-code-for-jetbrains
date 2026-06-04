package dev.lain.claudejb.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.lain.claudejb.protocol.TaskProgressInfo
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * The "live subagents" strip: one card per in-flight Task-tool subagent, shown above the composer next to
 * the queue/permission strips. Each card carries the subagent's name/description, its running
 * tokens / tool-use count / elapsed time, the last tool it touched, and a Stop (✕) button.
 *
 * Autonomous: it knows nothing about the session. [update] rebuilds the rows from a plain
 * `List<TaskProgressInfo>` (the host feeds it `session.subagentTasks.values` from `onStateChanged`);
 * clicking a card's ✕ invokes [onStop] with that task's `taskId`, which the host maps to its own stop call.
 *
 * The panel hides itself when there are no tasks, so it adds no vertical gap to the composer stack. Layout is
 * compact (one line of metadata, ellipsised text) to fit a narrow tool window.
 */
class SubagentTasksPanel(
    private val onStop: (taskId: String) -> Unit,
) : JPanel() {

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.emptyBottom(4)
        isVisible = false
    }

    /** Rebuilds the strip from the live subagent tasks (caller chooses the ordering). */
    fun update(tasks: List<TaskProgressInfo>) {
        removeAll()
        isVisible = tasks.isNotEmpty()
        tasks.forEach { add(taskCard(it)) }
        revalidate()
        repaint()
    }

    private fun taskCard(task: TaskProgressInfo): JPanel =
        ChatTheme.RoundedPanel(arc = 8, fill = ChatTheme.CARD_BG, line = ChatTheme.BORDER).apply {
            layout = BorderLayout(JBUI.scale(6), 0)
            border = JBUI.Borders.empty(4, 8)
            alignmentX = LEFT_ALIGNMENT
            add(textColumn(task), BorderLayout.CENTER)
            add(stopButton(task.taskId), BorderLayout.EAST)
        }

    private fun textColumn(task: TaskProgressInfo): JPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(headerRow(task))
            add(metaRow(task))
        }

    private fun headerRow(task: TaskProgressInfo): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(JBLabel("⚙").apply { foreground = ChatTheme.ACCENT })
            add(
                JBLabel(truncate(titleOf(task), TITLE_MAX_CHARS)).apply {
                    foreground = ChatTheme.TEXT
                    font = ChatTheme.small.asBold()
                    toolTipText = titleOf(task)
                },
            )
        }

    private fun metaRow(task: TaskProgressInfo): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(dim(metaLine(task)))
            task.lastToolName?.takeIf { it.isNotBlank() }?.let { add(dim("· $it")) }
            // A non-running lifecycle state (paused/failed/…) is worth flagging on the card, error in warning colour.
            statusLabel(task)?.let { add(it) }
        }

    /** A coloured chip for a non-running status (or an error), or null while the subagent is simply running. */
    private fun statusLabel(task: TaskProgressInfo): JBLabel? {
        val status = task.status?.takeIf { it.isNotBlank() && it != "running" }
        val err = task.error?.takeIf { it.isNotBlank() }
        if (status == null && err == null) return null
        val text = "· " + (err?.let { "${status ?: "failed"}: ${truncate(it, ERROR_MAX_CHARS)}" } ?: status!!)
        return JBLabel(text).apply {
            foreground = if (err != null || status == "failed") ChatTheme.WARNING else ChatTheme.TEXT_DIM
            font = ChatTheme.small
            toolTipText = err ?: status
        }
    }

    private fun dim(text: String): JBLabel =
        JBLabel(text).apply {
            foreground = ChatTheme.TEXT_DIM
            font = ChatTheme.small
        }

    private fun stopButton(taskId: String): JButton =
        JButton("✕").apply {
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            isOpaque = false
            foreground = ChatTheme.TEXT_DIM
            font = ChatTheme.small
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Stop this subagent"
            margin = JBUI.insets(2, 8)
            addActionListener { onStop(taskId) }
        }

    /** The subagent display name: its type when present, else the (truncated) description. */
    private fun titleOf(task: TaskProgressInfo): String =
        task.subagentType?.takeIf { it.isNotBlank() }?.let { type ->
            if (task.description.isNotBlank()) "$type — ${task.description}" else type
        } ?: task.description.ifBlank { task.taskId }

    private fun metaLine(task: TaskProgressInfo): String {
        val u = task.usage
        return "${formatTokens(u.totalTokens)} tok · ${u.toolUses} tools · ${formatDuration(u.durationMs)}"
    }

    private fun truncate(s: String, max: Int): String =
        s.replace('\n', ' ').let { if (it.length > max) it.take(max) + "…" else it }

    companion object {
        /** Max characters of a subagent title shown before truncation (narrow tool window). */
        private const val TITLE_MAX_CHARS = 48

        /** Max characters of a subagent error message shown on the card before truncation. */
        private const val ERROR_MAX_CHARS = 40

        /** Compact token count (`940`, `1.2k`, `3.4M`) — delegates to the shared [TokenFormat]. Pure for testing. */
        fun formatTokens(tokens: Long): String = TokenFormat.format(tokens)

        /**
         * Compact elapsed time: `0s`, `850ms`→`0s` rounds, `45s`, `2m 05s`, `1h 03m`. Pure for testing.
         * Negative inputs clamp to 0.
         */
        fun formatDuration(durationMs: Long): String {
            val totalSec = (durationMs.coerceAtLeast(0)) / 1_000
            val h = totalSec / 3_600
            val m = (totalSec % 3_600) / 60
            val s = totalSec % 60
            return when {
                h > 0 -> "${h}h %02dm".format(m)
                m > 0 -> "${m}m %02ds".format(s)
                else -> "${s}s"
            }
        }
    }
}
