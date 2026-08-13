package dev.lain.claudejb.ui

import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.FormBuilder
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.Provider
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JList

/**
 * Which API provider a session runs against, and that provider's own key.
 *
 * The key is the one thing on this page that never reaches the settings document: it goes to the IDE password
 * safe, in the selected provider's OWN slot, so a DeepSeek key and an Anthropic key can never overwrite each
 * other. That is also why this section needs the [settings] service and not just the state.
 */
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
        // A third-party provider MUST carry its own key — without it we'd emit nothing and the binary would
        // fall back to your Anthropic login (which doesn't work there). And the key must NOT be an Anthropic
        // key: your Anthropic credentials are never used for another provider.
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
        // Save only the selected provider's own key (Anthropic has none; leave other providers' keys intact).
        if (provider.requiresApiKey) settings.setProviderApiKey(provider, apiKey)
    }

    override fun changedFields(s: ClaudeSettings.State): List<Boolean> {
        val provider = selectedProvider()
        // The key only participates when the provider actually has one; otherwise it is not a difference.
        val apiKeyChanged = provider.requiresApiKey &&
            String(apiKeyField.password).trim() != settings.getProviderApiKey(provider)
        return listOf(
            provider.id != s.provider,
            apiKeyChanged,
        )
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

    private fun providerWarningLabel() = noteLabel(
        "<b>Anthropic</b> uses the <code>claude</code> binary's own login (subscription/OAuth). A non-Anthropic " +
            "provider (e.g. <b>DeepSeek</b>) routes to its Anthropic-compatible endpoint and <b>requires its own " +
            "issued key</b> — your Anthropic credentials are <b>never</b> reused for another provider. The key is " +
            "stored in the IDE <b>password safe</b>, in that provider's own slot. Changing the provider restarts " +
            "the session.",
    )
}
