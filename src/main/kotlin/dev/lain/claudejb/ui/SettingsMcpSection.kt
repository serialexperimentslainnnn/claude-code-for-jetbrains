package dev.lain.claudejb.ui

import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.settings.ClaudeSettings
import javax.swing.JComboBox
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

internal class SettingsMcpSection : SettingsSection {

    private val ideMcpCheck = JBCheckBox("Enable JetBrains MCP server — lets Claude query the IDE")
    private val ideMcpTransportCombo = JComboBox(ClaudeSession.IDE_MCP_TRANSPORTS.toTypedArray())
    private val ideMcpPortSpinner =
        JSpinner(SpinnerNumberModel(ClaudeSession.DEFAULT_IDE_MCP_PORT, MIN_PORT, MAX_PORT, 1))
    private val customMcpArea = JBTextArea(CUSTOM_MCP_ROWS, 0).apply {
        emptyText.text = "JSON object of name → server config; add as many as you like (sse / streamable-http / stdio)"
    }
    private val strictMcpCheck = JBCheckBox("Strict MCP config (only use servers from --mcp-config)")

    override fun addTo(panel: Panel) {
        panel.collapsibleGroup("MCP") {
            row { cell(ideMcpCheck) }
            row("Transport:") { cell(ideMcpTransportCombo) }
            row("Port:") { cell(ideMcpPortSpinner) }
                .rowComment(JETBRAINS_MCP_NOTE, MAX_LINE_LENGTH_WORD_WRAP)
            row("Custom servers:") { scrollCell(customMcpArea).align(AlignX.FILL) }
                .rowComment(CUSTOM_MCP_NOTE, MAX_LINE_LENGTH_WORD_WRAP)
            row { cell(strictMcpCheck) }
        }
    }

    override fun reset(s: ClaudeSettings.State) {
        ideMcpCheck.isSelected = s.ideMcpEnabled
        ideMcpTransportCombo.selectedItem = s.ideMcpTransport
        ideMcpPortSpinner.value = s.ideMcpPort
        customMcpArea.text = s.customMcpServers
        strictMcpCheck.isSelected = s.strictMcpConfig
    }

    override fun validate() {
        if (!ClaudeSession.isValidMcpConfig(customMcpArea.text.trim())) {
            throw ConfigurationException("Custom MCP servers must be a JSON object mapping each server name to its config.")
        }
    }

    override fun apply(s: ClaudeSettings.State) {
        s.ideMcpEnabled = ideMcpCheck.isSelected
        s.ideMcpTransport = mcpTransportText()
        s.ideMcpPort = mcpPortValue()
        s.customMcpServers = customMcpArea.text.trim()
        s.strictMcpConfig = strictMcpCheck.isSelected
    }

    override fun changedFields(s: ClaudeSettings.State): List<Boolean> = listOf(
        ideMcpCheck.isSelected != s.ideMcpEnabled,
        mcpTransportText() != s.ideMcpTransport,
        mcpPortValue() != s.ideMcpPort,
        customMcpArea.text.trim() != s.customMcpServers,
        strictMcpCheck.isSelected != s.strictMcpConfig,
    )

    private fun mcpTransportText() = (ideMcpTransportCombo.selectedItem as? String) ?: "sse"
    private fun mcpPortValue() = (ideMcpPortSpinner.value as Number).toInt()

    private companion object {
        const val MIN_PORT = 1
        const val MAX_PORT = 65_535

        const val CUSTOM_MCP_ROWS = 7

        const val JETBRAINS_MCP_NOTE =
            "⚠ Requires JetBrains' own MCP Server plugin. <code>sse</code> and <code>streamable-http</code> expose " +
                "a localhost port any local process can reach; <code>stdio</code> launches a helper instead. Tool " +
                "calls are still gated by the permission prompt and by the guard."

        const val CUSTOM_MCP_NOTE =
            "A JSON object of name → server config: <code>{ \"name\": { \"type\": \"…\", … } }</code>. " +
                "⚠ Third-party servers run with your privileges and can read what you share with them."
    }
}
