package dev.lain.claudejb.ui

import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.FormBuilder
import dev.lain.claudejb.forge.ForgeTokens
import dev.lain.claudejb.git.GitHistoryService
import dev.lain.claudejb.settings.ClaudeSettings

/**
 * The access token the Git view's pull-request and pipeline cards are fetched with.
 *
 * **Per HOST, and the host is not a field the user types.** It is read from the repository this project is
 * actually pointing at, so a company GitLab and gitlab.com are two credentials and never one, and nobody can
 * paste a token against the wrong server by mistyping a name. With no repository, no remote, or a remote
 * whose URL names no host, there is nothing to key a token by and the row says so instead of offering a field
 * that would go nowhere.
 *
 * **Nothing here reaches [ClaudeSettings.State].** The token lives in the IDE's password safe under its host,
 * exactly like the provider API key, so [apply] writes it through [ForgeTokens] and [changedFields] compares
 * against what is stored rather than against the settings document. That is also why the field is not part of
 * the "every persisted field is claimed by exactly one section" contract: it is not a persisted field of that
 * document at all.
 *
 * An empty field CLEARS the token rather than meaning "leave it alone". The alternative — treating blank as
 * no-op — leaves a user who wants to revoke a credential with no way to do it from the page that stores it,
 * and "delete the text and press OK" is what everyone tries first.
 */
internal class SettingsForgeSection(private val history: () -> GitHistoryService?) : SettingsSection {

    private val tokenField = JBPasswordField()

    /**
     * Resolved ONCE per page, not per read.
     *
     * `apply` must write the token under the same host `reset` read it for. Re-resolving would let a branch
     * switch or a remote edit between opening the page and pressing OK move the key underneath, which stores
     * the credential against a host the user was never shown.
     */
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

    /**
     * Writes the token to the safe.
     *
     * The section contract says `apply` must not persist, so that one section cannot leave the form half
     * written when a later one throws — and this is the same deliberate exception the provider's API key
     * makes: the value does not live in the document the page saves at the end, so there is no later save
     * that would carry it.
     */
    override fun apply(s: ClaudeSettings.State) {
        val h = host ?: return
        val typed = String(tokenField.password).trim()
        if (typed.isEmpty()) ForgeTokens.clear(h) else ForgeTokens.set(h, typed)
    }

    override fun changedFields(s: ClaudeSettings.State): List<Boolean> = listOf(
        host != null && String(tokenField.password).trim() != host?.let { ForgeTokens.get(it) }.orEmpty(),
    )
}
