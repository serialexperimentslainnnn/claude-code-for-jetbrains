package dev.lain.claudejb.ui

import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.settings.ClaudeSettings
import javax.swing.JComboBox
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * The JetBrains MCP server (opt-in) and any number of custom MCP servers.
 *
 * The custom-server JSON is [validate]d before anything is written: an unparseable object here is what the
 * trust gate falls back to treating as "no extra risk", so the page is where it has to be caught.
 */
internal class SettingsMcpSection : SettingsSection {

    private val ideMcpCheck = JBCheckBox("Enable JetBrains MCP server — lets Claude query the IDE")
    private val ideMcpTransportCombo = JComboBox(ClaudeSession.IDE_MCP_TRANSPORTS.toTypedArray())
    private val ideMcpPortSpinner =
        JSpinner(SpinnerNumberModel(ClaudeSession.DEFAULT_IDE_MCP_PORT, MIN_PORT, MAX_PORT, 1))
    private val customMcpArea = JBTextArea(CUSTOM_MCP_ROWS, 0).apply {
        emptyText.text = "JSON object of name → server config; add as many as you like (sse / streamable-http / stdio)"
    }
    private val strictMcpCheck = JBCheckBox("Strict MCP config (only use servers from --mcp-config)")

    override fun addTo(form: FormBuilder): FormBuilder = form
        .addSeparator()
        .addComponent(sectionLabel("JetBrains MCP server (opt-in) — requires the MCP Server plugin enabled"))
        .addComponent(ideMcpCheck)
        .addLabeledComponent("Transport:", ideMcpTransportCombo)
        .addLabeledComponent("Port:", ideMcpPortSpinner)
        .addComponent(jetbrainsMcpWarningLabel())
        .addSeparator()
        .addComponent(sectionLabel("Custom MCP servers (advanced) — add any number"))
        .addComponent(JBScrollPane(customMcpArea))
        .addComponent(customMcpWarningLabel())
        .addComponent(strictMcpCheck)

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

    private fun jetbrainsMcpWarningLabel() = noteLabel(
        "⚠ <b>Security:</b> requires JetBrains' MCP Server plugin enabled. sse / streamable-http expose a " +
            "localhost port any local process can reach; stdio launches a helper from the IDE (no port). Enable only " +
            "on a machine you trust. Tool calls are still gated by the permission prompt.",
    )

    private fun customMcpWarningLabel() = noteLabel(
        "Format: <code>{ \"server-name\": { \"type\": \"…\", … }, … }</code>. " +
            "⚠ third-party servers run with your privileges and can read what you share — add only ones you trust.",
    )

    private companion object {
        /** TCP port bounds for the JetBrains MCP server spinner (0 is not a listenable port). */
        const val MIN_PORT = 1
        const val MAX_PORT = 65_535

        /** Visible rows of the custom-server JSON area, i.e. how tall it is before scrolling. */
        const val CUSTOM_MCP_ROWS = 7
    }
}
