package dev.lain.claudejb.ui

import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
import dev.lain.claudejb.forge.ForgeTokens
import dev.lain.claudejb.git.GitHistoryService
import dev.lain.claudejb.settings.ClaudeSettings

internal class SettingsForgeSection(private val history: () -> GitHistoryService?) : SettingsSection {

    private val tokenField = JBPasswordField()

    private val host: String? by lazy { history()?.primaryRemote()?.host }

    override fun addTo(panel: Panel) {
        panel.group("Git forge") {
            row(host?.let { "Access token for $it:" } ?: "Access token:") {
                cell(tokenField).align(AlignX.FILL)
            }.rowComment(note(), MAX_LINE_LENGTH_WORD_WRAP)
        }
    }

    private fun note(): String = when (val h = host) {
        null ->
            "No Git remote to read, so there is nothing to store a token for. Open a project whose remote " +
                "names a host and this field will be for that host."

        else ->
            "Stored in the IDE's password safe under <code>$h</code>, never in a project file. It reads this " +
                "branch's open pull requests and its last CI run, which the Git view then shows; without it " +
                "those two cards are simply absent. Clear the field to remove the token."
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
