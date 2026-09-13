package dev.lain.claudejb.view.settings.sections

import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.model.settings.LaunchDefaults
import dev.lain.claudejb.view.settings.SettingsSection

internal class SettingsMcpSection : SettingsSection {

    private val customMcpArea = JBTextArea(CUSTOM_MCP_ROWS, 0).apply {
        emptyText.text = "JSON object of name → server config; add as many as you like (sse / streamable-http / stdio)"
    }
    private val strictMcpCheck = JBCheckBox("Strict MCP config (only use servers from --mcp-config)")

    override fun addTo(panel: Panel) {
        panel.collapsibleGroup("Custom MCP Servers") {
            row("Custom servers:") { scrollCell(customMcpArea).align(AlignX.FILL) }
                .rowComment(CUSTOM_MCP_NOTE, MAX_LINE_LENGTH_WORD_WRAP)
            row { cell(strictMcpCheck) }
        }
    }

    override fun reset(s: ClaudeSettings.State) {
        customMcpArea.text = s.customMcpServers
        strictMcpCheck.isSelected = s.strictMcpConfig
    }

    override fun validate() {
        if (!LaunchDefaults.isValidMcpConfig(customMcpArea.text.trim())) {
            throw ConfigurationException("Custom MCP servers must be a JSON object mapping each server name to its config.")
        }
    }

    override fun apply(s: ClaudeSettings.State) {
        s.customMcpServers = customMcpArea.text.trim()
        s.strictMcpConfig = strictMcpCheck.isSelected
    }

    override fun changedFields(s: ClaudeSettings.State): List<Boolean> = listOf(
        customMcpArea.text.trim() != s.customMcpServers,
        strictMcpCheck.isSelected != s.strictMcpConfig,
    )

    private companion object {
        const val CUSTOM_MCP_ROWS = 7

        const val CUSTOM_MCP_NOTE =
            "A JSON object of name → server config: <code>{ \"name\": { \"type\": \"…\", … } }</code>. " +
                "⚠ Third-party servers run with your privileges and can read what you share with them."
    }
}
