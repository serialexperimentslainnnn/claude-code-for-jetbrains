package dev.lain.claudejb.ui

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.ui.scale.JBUIScale
import dev.lain.claudejb.protocol.ModelInfo
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.SessionListener
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.Provider
import java.awt.GridLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
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
    private val thinkingCheck = JBCheckBox("Extended thinking (adaptive — the model decides depth)")
    private val partialCheck = JBCheckBox("Stream partial messages (live token streaming)")
    private val restoreChatsCheck = JBCheckBox("Restore open chats on startup")

    private val providerCombo = JComboBox(Provider.entries.toTypedArray()).apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ): java.awt.Component = super.getListCellRendererComponent(
                list, (value as? Provider)?.label ?: value, index, isSelected, cellHasFocus,
            )
        }
        addActionListener { onProviderSelectionChanged() }
    }
    private val apiKeyField = JBPasswordField().apply {
        emptyText.text = "Required for non-Anthropic providers — paste the provider's own issued key"
    }
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

    private val maxTurnsSpinner = JSpinner(SpinnerNumberModel(0, 0, 1_000, 1))
    private val maxBudgetSpinner = JSpinner(SpinnerNumberModel(0.0, 0.0, 10_000.0, 0.5))
    private val fallbackModelField = JBTextField().apply {
        emptyText.text = "Optional model to retry with on overload (e.g. sonnet); blank = none"
    }
    private val addDirsArea = JBTextArea(3, 0).apply {
        emptyText.text = "Extra accessible directories, one absolute path per line; blank = project root only"
    }
    private val betasField = JBTextField().apply {
        emptyText.text = "Comma-separated beta feature flags; blank = none"
    }
    private val strictMcpCheck = JBCheckBox("Strict MCP config (only use servers from --mcp-config)")

    private val alwaysAllowModel = CollectionListModel<String>()
    private val alwaysAllowList = JBList(alwaysAllowModel).apply {
        emptyText.text = "No tools are auto-approved — every tool call shows a permission card."
        visibleRowCount = 4
    }

    private val settingSourcesGroup = CheckboxGroup(ClaudeSession.SETTING_SOURCES, columns = 3)
    private val allowedToolsGroup = CheckboxGroup(ClaudeSession.BUILTIN_TOOLS, columns = 4)
    private val disallowedToolsGroup = CheckboxGroup(ClaudeSession.BUILTIN_TOOLS, columns = 4)

    private var panel: JPanel? = null

    /** Snapshot of the current session's model list, consulted by [modelRenderer] to pretty-print values. */
    private var currentModels: List<ModelInfo> = emptyList()

    /** SessionListener kept so the combo repopulates when `initialize` lands with fresh models. Disposed below. */
    private var modelListener: SessionListener? = null
    private var modelListenerSession: ClaudeSession? = null

    /** Renders a model value (e.g. `default`, `sonnet`) as its human label ("Default (recommended)"); falls back
     *  to the raw value for custom/unknown entries. Only affects the dropdown popup — the editable text field
     *  still shows the value (what we send to the binary), so power users can type a custom id. */
    private val modelRenderer = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
        ): java.awt.Component {
            val raw = (value as? String).orEmpty()
            val pretty = currentModels.firstOrNull { it.value == raw }?.displayName?.ifBlank { null } ?: raw
            return super.getListCellRendererComponent(list, pretty, index, isSelected, cellHasFocus)
        }
    }

    override fun getDisplayName(): String = "Claude Code"

    override fun createComponent(): JComponent {
        modelCombo.renderer = modelRenderer
        modeCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ): java.awt.Component {
                val label = dev.lain.claudejb.session.PermissionMode.labelFor(value as? String)
                return super.getListCellRendererComponent(list, label, index, isSelected, cellHasFocus)
            }
        }
        rebuildModelCombo()
        ensureModelListener()
        val built = FormBuilder.createFormBuilder()
            .addLabeledComponent("Model:", modelCombo)
            .addLabeledComponent("Effort:", effortCombo)
            .addLabeledComponent("Permission mode:", modeCombo)
            .addComponent(thinkingCheck)
            .addComponent(partialCheck)
            .addComponent(restoreChatsCheck)
            .addSeparator()
            .addComponent(sectionLabel("API provider"))
            .addLabeledComponent("Provider:", providerCombo)
            .addLabeledComponent("API key:", apiKeyField)
            .addComponent(providerWarningLabel())
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
            .addComponent(sectionLabel("Always-allowed tools"))
            .addComponent(alwaysAllowedWarningLabel())
            .addComponent(alwaysAllowedComponent())
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
            .addSeparator()
            .addComponent(sectionLabel("Advanced launch (0 / blank = flag omitted)"))
            .addLabeledComponent("Max turns:", maxTurnsSpinner)
            .addLabeledComponent("Max budget (USD):", maxBudgetSpinner)
            .addLabeledComponent("Fallback model:", fallbackModelField)
            .addComponent(sectionLabel("Additional directories (one path per line)"))
            .addComponent(JBScrollPane(addDirsArea))
            .addLabeledComponent("Betas:", betasField)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        panel = built
        reset()
        // Pin the form to its preferred width on the LEFT instead of letting it stretch edge-to-edge: on a wide
        // (2K+) monitor a full-width form spread the text fields and the 4-column tool grids across the whole
        // screen. BorderLayout.WEST gives `built` its preferred width (now bounded, since the HTML notes wrap at
        // FORM_WIDTH) and leaves the rest of the page blank — a tidy, fixed-width settings column.
        val holder = JPanel(java.awt.BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 0, 0, JBUIScale.scale(12))
            add(built, java.awt.BorderLayout.WEST)
        }
        // Wrap in a scroll pane so the (long) form stays usable on small screens and doesn't force the Settings
        // dialog to balloon; the inner form keeps its preferred width and the viewport tracks it (responsive).
        return JBScrollPane(holder).apply {
            border = JBUI.Borders.empty()
            viewport.isOpaque = false
            isOpaque = false
            verticalScrollBar.unitIncrement = JBUIScale.scale(16)
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }
    }

    override fun isModified(): Boolean {
        val s = settings.state
        return modelText() != s.model ||
            effortText() != s.effort ||
            modeText() != s.permissionMode ||
            thinkingCheck.isSelected != (s.thinkingTokens > 0) ||
            partialCheck.isSelected != s.includePartialMessages ||
            restoreChatsCheck.isSelected != s.restoreOpenChatsOnStartup ||
            csvSet(settingSourcesGroup.text()) != csvSet(s.settingSources) ||
            csvSet(allowedToolsGroup.text()) != csvSet(s.allowedTools) ||
            csvSet(disallowedToolsGroup.text()) != csvSet(s.disallowedTools) ||
            selectedProvider().id != s.provider ||
            (selectedProvider().requiresApiKey &&
                String(apiKeyField.password).trim() != settings.getProviderApiKey(selectedProvider())) ||
            claudePathField.text.trim() != s.claudePath ||
            nodePathField.text.trim() != s.nodePath ||
            sourceScriptField.text.trim() != s.sourceScript ||
            envVarsArea.text != s.envVars ||
            ideMcpCheck.isSelected != s.ideMcpEnabled ||
            mcpTransportText() != s.ideMcpTransport ||
            mcpPortValue() != s.ideMcpPort ||
            customMcpArea.text.trim() != s.customMcpServers ||
            maxTurnsValue() != s.maxTurns ||
            maxBudgetValue() != s.maxBudgetUsd ||
            fallbackModelField.text.trim() != s.fallbackModel ||
            addDirsArea.text != s.addDirs ||
            betasField.text.trim() != s.betas ||
            strictMcpCheck.isSelected != s.strictMcpConfig ||
            alwaysAllowModel.items != settings.alwaysAllowedTools()
    }

    override fun apply() {
        if (!ClaudeSession.isValidMcpConfig(customMcpArea.text.trim())) {
            throw ConfigurationException("Custom MCP servers must be a JSON object mapping each server name to its config.")
        }
        val provider = selectedProvider()
        val apiKey = String(apiKeyField.password).trim()
        // A third-party provider MUST carry its own key — without it we'd emit nothing and the binary would
        // fall back to your Anthropic login (which doesn't work there). And the key must NOT be an Anthropic
        // key: your Anthropic credentials are never used for another provider.
        if (provider.requiresApiKey && apiKey.isEmpty()) {
            throw ConfigurationException(
                "${provider.label} requires its own API key. Enter the key, or switch the provider back to Anthropic."
            )
        }
        if (provider.requiresApiKey && Provider.looksLikeAnthropicKey(apiKey)) {
            throw ConfigurationException(
                "That looks like an Anthropic key (sk-ant-…). ${provider.label} needs a ${provider.label}-issued key — " +
                    "your Anthropic credentials are never used for another provider."
            )
        }
        val s = settings.state
        s.provider = provider.id
        // Save only the selected provider's own key (Anthropic has none; leave other providers' keys intact).
        if (provider.requiresApiKey) settings.setProviderApiKey(provider, apiKey)
        s.model = modelText()
        s.effort = effortText()
        s.permissionMode = modeText()
        s.thinkingTokens = if (thinkingCheck.isSelected) ClaudeSession.THINKING_ON else 0
        s.includePartialMessages = partialCheck.isSelected
        s.restoreOpenChatsOnStartup = restoreChatsCheck.isSelected
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
        s.maxTurns = maxTurnsValue()
        s.maxBudgetUsd = maxBudgetValue()
        s.fallbackModel = fallbackModelField.text.trim()
        s.addDirs = addDirsArea.text
        s.betas = betasField.text.trim()
        s.strictMcpConfig = strictMcpCheck.isSelected
        settings.setAlwaysAllowedTools(alwaysAllowModel.items.toList())
        settings.applyTo(session)
    }

    override fun reset() {
        val s = settings.state
        providerCombo.selectedItem = settings.provider
        onProviderSelectionChanged()
        modelCombo.selectedItem = s.model
        effortCombo.selectedItem = s.effort
        modeCombo.selectedItem = s.permissionMode
        thinkingCheck.isSelected = s.thinkingTokens > 0
        partialCheck.isSelected = s.includePartialMessages
        restoreChatsCheck.isSelected = s.restoreOpenChatsOnStartup
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
        maxTurnsSpinner.value = s.maxTurns
        maxBudgetSpinner.value = s.maxBudgetUsd
        fallbackModelField.text = s.fallbackModel
        addDirsArea.text = s.addDirs
        betasField.text = s.betas
        strictMcpCheck.isSelected = s.strictMcpConfig
        alwaysAllowModel.replaceAll(settings.alwaysAllowedTools())
    }

    /** Repopulate the model combo from the active session's `modelOptions()`, preserving the current selection
     *  (so an unsaved choice or a custom-typed value survives a refresh). Called once at create and again
     *  whenever the session reports fresh metadata (the binary's `initialize` lands asynchronously). */
    private fun rebuildModelCombo() {
        val opts = session.modelOptions()
        currentModels = opts
        val preserved = (modelCombo.editor?.item as? String)
            ?: (modelCombo.selectedItem as? String)
        modelCombo.model = DefaultComboBoxModel(opts.map { it.value }.toTypedArray())
        if (!preserved.isNullOrBlank()) modelCombo.selectedItem = preserved
    }

    /** Subscribe to the active session so the combo refreshes when `initialize` returns real models. Idempotent
     *  per session; swaps if the active session changes between Settings dialog opens. */
    private fun ensureModelListener() {
        val s = session
        if (modelListenerSession === s) return
        modelListener?.let { lst -> modelListenerSession?.removeListener(lst) }
        val lst = object : SessionListener {
            override fun onMetadataChanged() {
                javax.swing.SwingUtilities.invokeLater { rebuildModelCombo() }
            }
        }
        s.addListener(lst)
        modelListener = lst
        modelListenerSession = s
    }

    override fun disposeUIResources() {
        modelListener?.let { lst -> modelListenerSession?.removeListener(lst) }
        modelListener = null
        modelListenerSession = null
    }

    private fun selectedProvider(): Provider = providerCombo.selectedItem as? Provider ?: Provider.DEFAULT

    /**
     * Reflect the selected provider in the API-key field: enabled only for a third-party provider, and loaded
     * with THAT provider's own isolated stored key (so switching the combo shows each provider's key, and
     * Anthropic — which needs none — shows an empty, disabled field). Discards unsaved edits to the previously
     * shown key, which is the intended trade-off for per-provider isolation in a simple form.
     */
    private fun onProviderSelectionChanged() {
        val p = selectedProvider()
        apiKeyField.isEnabled = p.requiresApiKey
        apiKeyField.text = if (p.requiresApiKey) settings.getProviderApiKey(p) else ""
    }

    private fun modelText() = (modelCombo.editor.item as? String ?: modelCombo.selectedItem as? String).orEmpty().trim()
    private fun effortText() = (effortCombo.selectedItem as? String).orEmpty()
    private fun modeText() = (modeCombo.selectedItem as? String) ?: "default"
    private fun mcpTransportText() = (ideMcpTransportCombo.selectedItem as? String) ?: "sse"
    private fun mcpPortValue() = (ideMcpPortSpinner.value as Number).toInt()
    private fun maxTurnsValue() = (maxTurnsSpinner.value as Number).toInt()
    private fun maxBudgetValue() = (maxBudgetSpinner.value as Number).toDouble()

    private fun csvSet(s: String): Set<String> =
        s.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun sectionLabel(text: String) = JBLabel(text).apply { font = JBFont.medium().asBold() }

    /**
     * A small, **width-bounded** HTML note. The `width:` on the body forces the text to wrap at [FORM_WIDTH] instead
     * of demanding a single huge line — without it these one-line HTML labels blew the form's preferred width up to
     * the whole monitor (the Settings dialog opened enormous). [bodyHtml] is the inner markup (no <html>/<body>).
     */
    private fun noteLabel(bodyHtml: String) = JBLabel(
        "<html><body style='width:${FORM_WIDTH}px'>$bodyHtml</body></html>"
    ).apply { font = JBFont.small() }

    private fun providerWarningLabel() = noteLabel(
        "<b>Anthropic</b> uses the <code>claude</code> binary's own login (subscription/OAuth). A non-Anthropic " +
        "provider (e.g. <b>DeepSeek</b>) routes to its Anthropic-compatible endpoint and <b>requires its own " +
        "issued key</b> — your Anthropic credentials are <b>never</b> reused for another provider. The key is " +
        "stored in the IDE <b>password safe</b> (not in <code>claude-code.xml</code>). Changing the provider " +
        "restarts the session."
    )

    private fun envVarsWarningLabel() = noteLabel(
        "⚠ <b>Security:</b> these variables are stored <b>in plain text</b> in <code>claude-code.xml</code> " +
        "(may be committed to a repo or end up in backups). <b>Do not put secrets here</b> (API keys, tokens) — " +
        "use the source script above or the <code>claude</code> binary's native authentication instead."
    )

    private fun sourceScriptWarningLabel() = noteLabel(
        "⚠ <b>Security:</b> this script is <b>executed</b> when the session starts. Only point it at a script " +
        "you trust — do not run scripts that arrive with an untrusted project/repo."
    )

    private fun jetbrainsMcpWarningLabel() = noteLabel(
        "⚠ <b>Security:</b> requires JetBrains' MCP Server plugin enabled. sse / streamable-http expose a " +
        "localhost port any local process can reach; stdio launches a helper from the IDE (no port). Enable only " +
        "on a machine you trust. Tool calls are still gated by the permission prompt."
    )

    private fun customMcpWarningLabel() = noteLabel(
        "Format: <code>{ \"server-name\": { \"type\": \"…\", … }, … }</code>. " +
        "⚠ third-party servers run with your privileges and can read what you share — add only ones you trust."
    )

    private fun alwaysAllowedWarningLabel() = noteLabel(
        "⚠ <b>Security:</b> listed tools are auto-approved for this project without a prompt " +
        "(writes still stay within the project root). Select an entry and click <b>Remove</b> to revoke it."
    )

    /** Editable list of remembered "Always allow" tool names with a Remove action (revoke). */
    private fun alwaysAllowedComponent(): JComponent =
        ToolbarDecorator.createDecorator(alwaysAllowList)
            .setRemoveAction { alwaysAllowList.selectedValuesList.forEach { alwaysAllowModel.remove(it) } }
            .disableAddAction()
            .disableUpDownActions()
            .createPanel()

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

    private companion object {
        /** Fixed content width (CSS px) the form and its wrapping HTML notes are bounded to, so a wide monitor
         *  doesn't stretch the page edge-to-edge. */
        const val FORM_WIDTH = 600
    }
}
