package dev.lain.claudejb.ui

import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.FormBuilder
import dev.lain.claudejb.forge.ForgeTokens
import dev.lain.claudejb.git.GitHistoryService
import dev.lain.claudejb.settings.ClaudeSettings

internal class SettingsForgeSection(private val history: () -> GitHistoryService?) : SettingsSection {

    private val tokenField = JBPasswordField()

    private val host: String? by lazy { history()?.primaryRemote()?.host }

    override fun addTo(form: FormBuilder): FormBuilder {
        val label = host?.let { "Access token for $it:" } ?: "Access token:"
        return form
            .addComponent(sectionLabel("Git forge"))
            .addLabeledComponent(label, tokenField)
            .addComponent(noteLabel(note()))
    }

    private fun note(): String = when (val h = host) {
        null ->
            "No Git remote to read, so there is nothing to store a token for. Open a project with a " +
                "repository whose remote names a host, and this field will be for that host."

        else ->
            "Stored in the IDE's password safe under <code>$h</code>, never in a project file. It is used " +
                "only to read this branch's open pull requests and its last CI run, which the Git view then " +
                "shows. Without it those two cards are simply absent. Clear the field to remove the token."
    }

    override fun reset(s: ClaudeSettings.State) {
        tokenField.isEnabled = host != null
        tokenField.text = host?.let { ForgeTokens.get(it) }.orEmpty()
    }

    override fun apply(s: ClaudeSettings.State) {
        val h = host ?: return
        val typed = String(tokenField.password).trim()
        if (typed.isEmpty()) ForgeTokens.clear(h) else ForgeTokens.set(h, typed)
    }

    override fun changedFields(s: ClaudeSettings.State): List<Boolean> = listOf(
        host != null && String(tokenField.password).trim() != host?.let { ForgeTokens.get(it) }.orEmpty(),
    )
}
