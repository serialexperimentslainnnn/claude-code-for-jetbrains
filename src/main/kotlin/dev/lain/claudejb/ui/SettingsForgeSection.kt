package dev.lain.claudejb.ui

import com.intellij.ide.BrowserUtil
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
import dev.lain.claudejb.forge.ForgeTokenPages
import dev.lain.claudejb.forge.ForgeTokenReach
import dev.lain.claudejb.forge.ForgeTokens
import dev.lain.claudejb.git.GitHistoryService
import dev.lain.claudejb.git.GitRemoteProvider
import dev.lain.claudejb.settings.ClaudeSettings
import javax.swing.JButton

internal class SettingsForgeSection(private val history: () -> GitHistoryService?) : SettingsSection {

    private val tokenField = JBPasswordField()

    private val remote by lazy { history()?.primaryRemote() }

    private val host: String? by lazy { remote?.host }

    private val provider: GitRemoteProvider by lazy { remote?.provider ?: GitRemoteProvider.OTHER }

    private val pages by lazy {
        host?.let { h -> ForgeTokenReach.entries.flatMap { ForgeTokenPages.of(provider, h, it) } }.orEmpty()
    }

    private val buttons by lazy {
        pages.map { page -> JButton(page.label).apply { addActionListener { BrowserUtil.browse(page.url) } } }
    }

    override fun addTo(panel: Panel) {
        panel.group("Git forge") {
            row(host?.let { "Access token for $it:" } ?: "Access token:") {
                cell(tokenField).align(AlignX.FILL)
            }.rowComment(note(), MAX_LINE_LENGTH_WORD_WRAP)

            pages.forEachIndexed { index, page ->
                row { cell(buttons[index]) }.rowComment(page.note, MAX_LINE_LENGTH_WORD_WRAP)
            }
        }
    }

    private fun note(): String = when (val h = host) {
        null ->
            "No Git remote to read, so there is nothing to store a token for. Open a project whose remote " +
                "names a host and this field will be for that host."

        else -> stored(h) + reading() + acting()
    }

    private fun stored(h: String): String =
        "Stored in the IDE's password safe under <code>$h</code>, never in a project file. "

    private fun reading(): String =
        "Reading needs no more than read access: it lists this project's open merge or pull requests and " +
            "its pipeline runs, which the Git view shows in a tab each. "

    private fun acting(): String = when (provider) {
        GitRemoteProvider.OTHER ->
            "This build recognises GitHub and GitLab hosts by name; for anything else, paste a token and it " +
                "will be tried. Clear the field to remove it."

        else ->
            "Acting on them — retrying a run, approving, merging — needs a token with write access, and the " +
                "buttons below create either kind. Clear the field to remove the token."
    }

    override fun reset(s: ClaudeSettings.State) {
        tokenField.isEnabled = host != null
        buttons.forEach { it.isEnabled = host != null }
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
