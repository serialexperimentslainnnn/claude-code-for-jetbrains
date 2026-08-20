package dev.lain.claudejb.ui

import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
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

    override fun addTo(panel: Panel) {
        panel.group("API provider") {
            row("Provider:") { cell(providerCombo) }
            row("API key:") { cell(apiKeyField).align(AlignX.FILL) }
                .rowComment(PROVIDER_NOTE, MAX_LINE_LENGTH_WORD_WRAP)
        }
    }

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

    private companion object {
        const val PROVIDER_NOTE =
            "<b>Anthropic</b> uses the <code>claude</code> binary's own login. Any other provider routes to its " +
                "Anthropic-compatible endpoint and needs <b>its own issued key</b> — your Anthropic credentials are " +
                "never reused elsewhere. Keys live in the IDE password safe. Changing the provider restarts the " +
                "session."
    }
}
