package dev.lain.claudejb.view.settings.sections

import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
import dev.lain.claudejb.model.session.launch.GodMode
import dev.lain.claudejb.model.session.launch.IdeRule
import dev.lain.claudejb.model.session.launch.IdeServer
import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.view.settings.SettingsSection
import javax.swing.JButton

internal class SettingsIdeMcpSection : SettingsSection {

    private val ownServers = JBCheckBox(OWN_SERVERS_LABEL).apply { addActionListener { syncEnabled() } }
    private val approveClients = JBCheckBox("Ask me before an unexpected client may talk to our servers")
    private val mirror = JBCheckBox(MIRROR_LABEL)
    private val serverRules: Map<IdeServer, IdeRuleBoxes> = IdeServer.entries.associateWith { IdeRuleBoxes(IdeRule.forServer(it)) }
    private val commonRules = IdeRuleBoxes(IdeRule.common)

    private val enableAll = JButton("Turn " + GodMode.LABEL + " on — " + GodMode.TAGLINE).apply {
        addActionListener {
            ownServers.isSelected = true
            serverRules.values.forEach { it.checkAll() }
            commonRules.checkAll()
            syncEnabled()
        }
    }

    override fun addTo(panel: Panel) {
        panel.collapsibleGroup(TITLE) {
            row { cell(enableAll) }.rowComment(ENABLE_ALL_NOTE, MAX_LINE_LENGTH_WORD_WRAP)
            row { cell(ownServers) }.rowComment(OWN_SERVERS_NOTE, MAX_LINE_LENGTH_WORD_WRAP)
            row { cell(approveClients) }.rowComment(APPROVE_NOTE, MAX_LINE_LENGTH_WORD_WRAP)
            row { cell(mirror) }.rowComment(MIRROR_NOTE, MAX_LINE_LENGTH_WORD_WRAP)
            IdeServer.entries.forEach { server ->
                row(server.label + ":") { cell(serverRules.getValue(server).component) }
                    .rowComment(SERVER_NOTES.getValue(server), MAX_LINE_LENGTH_WORD_WRAP)
            }
            row("Always:") { cell(commonRules.component) }.rowComment(RULES_NOTE, MAX_LINE_LENGTH_WORD_WRAP)
        }
    }

    override fun reset(s: ClaudeSettings.State) {
        ownServers.isSelected = s.ideMcp.enabled
        approveClients.isSelected = s.ideMcp.approveClients
        mirror.isSelected = s.ideMcp.mirror
        val selected = IdeRule.parse(s.ideMcp.rules)
        serverRules.values.forEach { it.setFrom(selected) }
        commonRules.setFrom(selected)
        syncEnabled()
    }

    override fun apply(s: ClaudeSettings.State) {
        s.ideMcp.enabled = ownServers.isSelected
        s.ideMcp.approveClients = approveClients.isSelected
        s.ideMcp.mirror = mirror.isSelected
        s.ideMcp.rules = IdeRule.csv(selectedRules())
    }

    override fun changedFields(s: ClaudeSettings.State): List<Boolean> = listOf(
        ownServers.isSelected != s.ideMcp.enabled,
        approveClients.isSelected != s.ideMcp.approveClients,
        mirror.isSelected != s.ideMcp.mirror,
        selectedRules() != IdeRule.parse(s.ideMcp.rules),
    )

    private fun selectedRules(): Set<IdeRule> = serverRules.values.flatMap { it.selected() }.toSet() + commonRules.selected()

    private fun syncEnabled() {
        val on = ownServers.isSelected
        serverRules.values.forEach { it.setEnabled(on) }
        commonRules.setEnabled(on)
        mirror.isEnabled = on
    }

    private companion object {
        const val TITLE = "Claude IDE Integration"

        const val OWN_SERVERS_LABEL = "Enable this plugin's own servers — code, run, vcs and ops, over Unix sockets"

        const val OWN_SERVERS_NOTE =
            "Four MCP servers of this plugin's own, with no port and nothing to install. Each offers its tools on " +
                "demand, so the session pays only for the domains it uses."

        const val APPROVE_NOTE =
            "Our servers already refuse anyone without the session's token. This adds a notification with Allow " +
                "and Reject for a connection the plugin did not launch itself; unanswered, it is rejected."

        const val MIRROR_LABEL = "Mirror Claude's work in the IDE"

        const val MIRROR_NOTE =
            "What Claude reads opens in the preview tab, what it edits in a real one, a commit it names is selected in the " +
                "Log, a service in Services, a problem in its tab, a run in its window. Always without taking your focus: " +
                "the caret stays where you are typing and the Terminal keeps its tab."

        const val ENABLE_ALL_NOTE =
            "One switch, every server and every rule; it is on by default. Claude then reads, searches, edits, " +
                "refactors, builds, tests, debugs and commits through the IDE itself: faster, cheaper in tokens, and " +
                "the results land where you work. Fine-tune below; the flame in the chat bar lights when everything is on."

        val SERVER_NOTES: Map<IdeServer, String> = mapOf(
            IdeServer.CODE to "Reading, searching, navigating, editing, refactoring and formatting through the IDE's index.",
            IdeServer.RUN to "Builds, run configurations, tests, the Terminal tool window and the debugger.",
            IdeServer.VCS to "Git through the IDE, and its Log, Commit and Pull Requests views.",
            IdeServer.OPS to "The Services panel, databases, HTTP requests, SSH hosts, the project model and the IDE itself.",
        )

        const val RULES_NOTE =
            "Each rule adds one short instruction to Claude's system prompt, repeated on every turn as a hook so it " +
                "does not drift, naming the exact tools to use instead of its own Read, Edit and Bash."
    }
}
