package dev.lain.claudejb.ui

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBFont
import com.intellij.ui.scale.JBUIScale
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.settings.ClaudeSettings
import java.awt.GridLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Settings page (Settings ▸ Claude Code) exposing the launch defaults graphically. Applies changes to
 * the live session and persists them via [ClaudeSettings]. The list-valued options (setting sources,
 * allowed/disallowed tools) are pick-from-checkboxes rather than free text, so there's nothing to type.
 */
class ClaudeSettingsConfigurable(private val project: Project) : Configurable {

    private val settings = ClaudeSettings.getInstance(project)
    private val session: ClaudeSession get() = ChatSessionManager.getInstance(project).activeOrCreate()

    private val modelCombo = JComboBox<String>().apply { isEditable = true }
    private val effortCombo = JComboBox(ClaudeSession.EFFORT_LEVELS.toTypedArray())
    private val modeCombo = JComboBox(ClaudeSession.PERMISSION_MODES.toTypedArray())
    private val thinkingSpinner = JSpinner(SpinnerNumberModel(0, 0, 200_000, 1_000))
    private val partialCheck = JBCheckBox("Stream partial messages (live token streaming)")
    private val claudePathField = JBTextField().apply {
        emptyText.text = "Auto-detect (leave blank unless 'claude' is in a custom location)"
    }
    private val nodePathField = JBTextField().apply {
        emptyText.text = "Auto-detect (set only if Node is in a custom dir not on PATH — Windows npm installs)"
    }
    private val envVarsArea = JBTextArea(4, 0).apply {
        emptyText.text = "One KEY=VALUE per line (e.g. PATH=C:\\custom\\bin;%PATH%). Useful on Windows."
    }
    private val sourceScriptField = JBTextField().apply {
        emptyText.text = "Optional: .sh to source (Linux/macOS) or PowerShell profile/.ps1 to dot-source (Windows)"
    }

    private val ideMcpCheck = JBCheckBox("Enable JetBrains MCP server — lets Claude query the IDE")
    private val ideMcpTransportCombo = JComboBox(ClaudeSession.IDE_MCP_TRANSPORTS.toTypedArray())
    private val ideMcpPortSpinner = JSpinner(SpinnerNumberModel(ClaudeSession.DEFAULT_IDE_MCP_PORT, 1, 65535, 1))
    private val customMcpArea = JBTextArea(7, 0).apply {
        emptyText.text = "JSON object of name → server config; add as many as you like (sse / streamable-http / stdio)"
    }

    private val settingSourcesGroup = CheckboxGroup(ClaudeSession.SETTING_SOURCES, columns = 3)
    private val allowedToolsGroup = CheckboxGroup(ClaudeSession.BUILTIN_TOOLS, columns = 4)
    private val disallowedToolsGroup = CheckboxGroup(ClaudeSession.BUILTIN_TOOLS, columns = 4)

    private var panel: JPanel? = null

    override fun getDisplayName(): String = "Claude Code"

    override fun createComponent(): JComponent {
        modelCombo.model = DefaultComboBoxModel(session.modelOptions().map { it.value }.toTypedArray())
        val built = FormBuilder.createFormBuilder()
            .addLabeledComponent("Model:", modelCombo)
            .addLabeledComponent("Effort:", effortCombo)
            .addLabeledComponent("Permission mode:", modeCombo)
            .addLabeledComponent("Thinking tokens (0 = off):", thinkingSpinner)
            .addComponent(partialCheck)
            .addSeparator()
            .addLabeledComponent("claude executable path:", claudePathField)
            .addLabeledComponent("node executable path:", nodePathField)
            .addLabeledComponent("Source script:", sourceScriptField)
            .addComponent(sourceScriptWarningLabel())
            .addComponent(sectionLabel("Environment variables (KEY=VALUE per line)"))
            .addComponent(JBScrollPane(envVarsArea))
            .addComponent(envVarsWarningLabel())
            .addSeparator()
            .addComponent(sectionLabel("Setting sources (none = don't pass --setting-sources)"))
            .addComponent(settingSourcesGroup.component)
            .addComponent(sectionLabel("Allowed tools (none = all tools allowed)"))
            .addComponent(allowedToolsGroup.component)
            .addComponent(sectionLabel("Disallowed tools (none = nothing blocked)"))
            .addComponent(disallowedToolsGroup.component)
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
            .addComponentFillVertically(JPanel(), 0)
            .panel
        panel = built
        reset()
        return built
    }

    override fun isModified(): Boolean {
        val s = settings.state
        return modelText() != s.model ||
            effortText() != s.effort ||
            modeText() != s.permissionMode ||
            thinkingValue() != s.thinkingTokens ||
            partialCheck.isSelected != s.includePartialMessages ||
            csvSet(settingSourcesGroup.text()) != csvSet(s.settingSources) ||
            csvSet(allowedToolsGroup.text()) != csvSet(s.allowedTools) ||
            csvSet(disallowedToolsGroup.text()) != csvSet(s.disallowedTools) ||
            claudePathField.text.trim() != s.claudePath ||
            nodePathField.text.trim() != s.nodePath ||
            sourceScriptField.text.trim() != s.sourceScript ||
            envVarsArea.text != s.envVars ||
            ideMcpCheck.isSelected != s.ideMcpEnabled ||
            mcpTransportText() != s.ideMcpTransport ||
            mcpPortValue() != s.ideMcpPort ||
            customMcpArea.text.trim() != s.customMcpServers
    }

    override fun apply() {
        if (!ClaudeSession.isValidMcpConfig(customMcpArea.text.trim())) {
            throw ConfigurationException("Custom MCP servers must be a JSON object mapping each server name to its config.")
        }
        val s = settings.state
        s.model = modelText()
        s.effort = effortText()
        s.permissionMode = modeText()
        s.thinkingTokens = thinkingValue()
        s.includePartialMessages = partialCheck.isSelected
        s.settingSources = settingSourcesGroup.text()
        s.allowedTools = allowedToolsGroup.text()
        s.disallowedTools = disallowedToolsGroup.text()
        s.claudePath = claudePathField.text.trim()
        s.nodePath = nodePathField.text.trim()
        s.sourceScript = sourceScriptField.text.trim()
        s.envVars = envVarsArea.text
        s.ideMcpEnabled = ideMcpCheck.isSelected
        s.ideMcpTransport = mcpTransportText()
        s.ideMcpPort = mcpPortValue()
        s.customMcpServers = customMcpArea.text.trim()
        settings.applyTo(session)
    }

    override fun reset() {
        val s = settings.state
        modelCombo.selectedItem = s.model
        effortCombo.selectedItem = s.effort
        modeCombo.selectedItem = s.permissionMode
        thinkingSpinner.value = s.thinkingTokens
        partialCheck.isSelected = s.includePartialMessages
        settingSourcesGroup.setFrom(s.settingSources)
        allowedToolsGroup.setFrom(s.allowedTools)
        disallowedToolsGroup.setFrom(s.disallowedTools)
        claudePathField.text = s.claudePath
        nodePathField.text = s.nodePath
        sourceScriptField.text = s.sourceScript
        envVarsArea.text = s.envVars
        ideMcpCheck.isSelected = s.ideMcpEnabled
        ideMcpTransportCombo.selectedItem = s.ideMcpTransport
        ideMcpPortSpinner.value = s.ideMcpPort
        customMcpArea.text = s.customMcpServers
    }

    private fun modelText() = (modelCombo.editor.item as? String ?: modelCombo.selectedItem as? String).orEmpty().trim()
    private fun effortText() = (effortCombo.selectedItem as? String).orEmpty()
    private fun modeText() = (modeCombo.selectedItem as? String) ?: "default"
    private fun thinkingValue() = (thinkingSpinner.value as Number).toInt()
    private fun mcpTransportText() = (ideMcpTransportCombo.selectedItem as? String) ?: "sse"
    private fun mcpPortValue() = (ideMcpPortSpinner.value as Number).toInt()

    private fun csvSet(s: String): Set<String> =
        s.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun sectionLabel(text: String) = JBLabel(text).apply { font = JBFont.medium().asBold() }

    private fun envVarsWarningLabel() = JBLabel(
        "<html>⚠ <b>Security:</b> these variables are stored <b>in plain text</b> in <code>claude-code.xml</code> " +
        "(may be committed to a repo or end up in backups). <b>Do not put secrets here</b> (API keys, tokens) — " +
        "use the source script above or the <code>claude</code> binary's native authentication instead.</html>"
    ).apply { font = JBFont.small() }

    private fun sourceScriptWarningLabel() = JBLabel(
        "<html>⚠ <b>Security:</b> this script is <b>executed</b> when the session starts. Only point it at a script " +
        "you trust — do not run scripts that arrive with an untrusted project/repo.</html>"
    ).apply { font = JBFont.small() }

    private fun jetbrainsMcpWarningLabel() = JBLabel(
        "<html>⚠ <b>Security:</b> requires JetBrains' MCP Server plugin enabled. sse / streamable-http expose a " +
        "localhost port any local process can reach; stdio launches a helper from the IDE (no port). Enable only " +
        "on a machine you trust. Tool calls are still gated by the permission prompt.</html>"
    ).apply { font = JBFont.small() }

    private fun customMcpWarningLabel() = JBLabel(
        "<html>Format: <code>{ \"server-name\": { \"type\": \"…\", … }, … }</code>. " +
        "⚠ third-party servers run with your privileges and can read what you share — add only ones you trust.</html>"
    ).apply { font = JBFont.small() }

    /** A row/grid of checkboxes backed by a comma-separated value — the GUI form of a list option. */
    private class CheckboxGroup(options: List<String>, columns: Int) {
        private val boxes = LinkedHashMap<String, JBCheckBox>().apply {
            options.forEach { put(it, JBCheckBox(it)) }
        }
        val component: JComponent = JPanel(GridLayout(0, columns, JBUIScale.scale(8), JBUIScale.scale(2))).apply {
            boxes.values.forEach { add(it) }
        }

        fun text(): String = boxes.filterValues { it.isSelected }.keys.joinToString(",")

        fun setFrom(csv: String) {
            val selected = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            boxes.forEach { (name, box) -> box.isSelected = name in selected }
        }
    }
}
