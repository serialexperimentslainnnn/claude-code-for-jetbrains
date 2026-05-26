package dev.lain.claudejb.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import dev.lain.claudejb.protocol.SlashCommand
import dev.lain.claudejb.session.ClaudeSession
import javax.swing.JComponent

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
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(commands)
            .setTitle("Slash commands")
            .setRenderer(SimpleListCellRenderer.create("") { cmd ->
                val hint = if (cmd.argumentHint.isNotBlank()) "  ${cmd.argumentHint}" else ""
                "/${cmd.name}$hint    —    ${cmd.description}"
            })
            .setNamerForFiltering { "${it.name} ${it.description} ${it.aliases.joinToString(" ")}" }
            .setItemChosenCallback { onPick(it) }
            .setRequestFocus(true)
            .createPopup()
            .showUnderneathOf(anchor)
    }
}
