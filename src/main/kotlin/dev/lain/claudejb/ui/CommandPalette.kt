package dev.lain.claudejb.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.scale.JBUIScale
import dev.lain.claudejb.protocol.SlashCommand
import dev.lain.claudejb.session.ClaudeSession
import java.awt.Dimension
import java.awt.Point
import javax.swing.JComponent
import javax.swing.JList

/**
 * Graphical picker for **every** slash command Claude Code reports (from the initialize handshake),
 * with speed-search filtering over name/description/aliases. Replaces the CLI's `/` menu.
 */
object CommandPalette {

    /** Commands the REPL handles client-side and the SDK does not report in the initialize handshake. */
    private val CLIENT_SIDE = listOf(
        SlashCommand(
            name = "btw",
            description = "Ask a quick side question without interrupting Claude's current work",
            argumentHint = "<your question>",
        ),
    )

    fun show(project: Project, anchor: JComponent, session: ClaudeSession, onPick: (SlashCommand) -> Unit) {
        val commands = CLIENT_SIDE + session.commands
        if (commands.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "Slash commands become available once the session has connected.",
                "Claude Code",
            )
            return
        }

        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(commands)
            .setTitle("Slash commands")
            .setRenderer(object : SimpleListCellRenderer<SlashCommand>() {
                override fun customize(list: JList<out SlashCommand>, cmd: SlashCommand?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    cmd ?: return
                    val hint = if (cmd.argumentHint.isNotBlank()) "  ${cmd.argumentHint}" else ""
                    val desc = cmd.description.let { if (it.length > 72) it.take(72) + "…" else it }
                    text = "/${cmd.name}$hint  —  $desc"
                }
            })
            .setNamerForFiltering { "${it.name} ${it.description} ${it.aliases.joinToString(" ")}" }
            .setItemChosenCallback { onPick(it) }
            .setRequestFocus(true)
            .createPopup()

        // Walk up the component tree to find the chat panel's actual width: the first ancestor
        // significantly wider than the input textarea is the card or outer panel.
        var c: java.awt.Component? = anchor.parent
        while (c != null && c.width <= anchor.width + JBUIScale.scale(40)) c = c.parent
        val popupWidth = c?.width ?: JBUIScale.scale(500)

        // Constrain width and measure height BEFORE showing, so we can place above the anchor.
        val content = popup.content
        content.preferredSize = Dimension(popupWidth, content.preferredSize.height)
        val popupH = content.preferredSize.height

        // Show above the input, left-aligned with the chat panel (same x as anchor).
        popup.show(RelativePoint(anchor, Point(0, -popupH)))
    }
}
