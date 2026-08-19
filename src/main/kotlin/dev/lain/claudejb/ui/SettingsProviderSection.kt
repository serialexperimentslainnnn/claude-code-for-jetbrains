package dev.lain.claudejb.ui

import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.FormBuilder
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.Provider
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JList

internal class SettingsProviderSection(private val settings: ClaudeSettings) : SettingsSection {

    private val providerCombo = JComboBox(Provider.entries.toTypedArray()).apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component = super.getListCellRendererComponent(
                list,
                (value as? Provider)?.label ?: value,
                index,
                isSelected,
                cellHasFocus,
            )
        }
        addActionListener { onProviderSelectionChanged() }
    }
    private val apiKeyField = JBPasswordField().apply {
        emptyText.text = "Required for non-Anthropic providers — paste the provider's own issued key"
    }

    override fun addTo(form: FormBuilder): FormBuilder = form
        .addSeparator()
        .addComponent(sectionLabel("API provider"))
        .addLabeledComponent("Provider:", providerCombo)
        .addLabeledComponent("API key:", apiKeyField)
        .addComponent(providerWarningLabel())

    override fun reset(s: ClaudeSettings.State) {
        providerCombo.selectedItem = settings.provider
        onProviderSelectionChanged()
    }

    override fun validate() {
        val provider = selectedProvider()
        val apiKey = String(apiKeyField.password).trim()
        if (provider.requiresApiKey && apiKey.isEmpty()) {
            throw ConfigurationException(
                "${provider.label} requires its own API key. Enter the key, or switch the provider back to Anthropic.",
            )
        }
        if (provider.requiresApiKey && Provider.looksLikeAnthropicKey(apiKey)) {
            throw ConfigurationException(
                "That looks like an Anthropic key (sk-ant-…). ${provider.label} needs a ${provider.label}-issued key — " +
                    "your Anthropic credentials are never used for another provider.",
            )
        }
    }

    override fun apply(s: ClaudeSettings.State) {
        val provider = selectedProvider()
        val apiKey = String(apiKeyField.password).trim()
        s.provider = provider.id
        if (provider.requiresApiKey) settings.setProviderApiKey(provider, apiKey)
    }

    override fun changedFields(s: ClaudeSettings.State): List<Boolean> {
        val provider = selectedProvider()
        val apiKeyChanged = provider.requiresApiKey &&
            String(apiKeyField.password).trim() != settings.getProviderApiKey(provider)
        return listOf(
            provider.id != s.provider,
            apiKeyChanged,
        )
    }

    private fun selectedProvider(): Provider = providerCombo.selectedItem as? Provider ?: Provider.DEFAULT

    private fun onProviderSelectionChanged() {
        val p = selectedProvider()
        apiKeyField.isEnabled = p.requiresApiKey
        apiKeyField.text = if (p.requiresApiKey) settings.getProviderApiKey(p) else ""
    }

    private fun providerWarningLabel() = noteLabel(
        "<b>Anthropic</b> uses the <code>claude</code> binary's own login (subscription/OAuth). A non-Anthropic " +
            "provider (e.g. <b>DeepSeek</b>) routes to its Anthropic-compatible endpoint and <b>requires its own " +
            "issued key</b> — your Anthropic credentials are <b>never</b> reused for another provider. The key is " +
            "stored in the IDE <b>password safe</b>, in that provider's own slot. Changing the provider restarts " +
            "the session.",
    )
}
