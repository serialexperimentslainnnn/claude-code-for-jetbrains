package dev.lain.claudejb.ui

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.util.ui.FormBuilder
import dev.lain.claudejb.settings.ClaudeSettings
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings ▸ **Claude Code Security** — the guard, on its own page.
 *
 * Its own entry rather than a section of the main page because it is the surface a user comes to on purpose,
 * usually while something is being blocked, and because it is the one page where every control has a
 * consequence for what can reach the machine. It reads and writes the same per-project document as the main
 * page, so a second project's rules are a second project's rules.
 *
 * It deliberately does not touch a [dev.lain.claudejb.session.ClaudeSession]: nothing here is a launch
 * option, the policy is rebuilt from settings on every single tool call, and asking for the active chat
 * would create one just because somebody opened Settings.
 */
class ClaudeSecurityConfigurable(private val project: Project) : Configurable {

    private val settings = ClaudeSettings.getInstance(project)

    private val masterSection = SettingsGuardMasterSection()
    private val rulesSection = SettingsSecuritySection(settings)

    private val sections: List<SettingsSection> = listOf(masterSection, rulesSection)

    override fun getDisplayName(): String = "Claude Code Security"

    private var shown: ClaudeSettings.State? = null

    private val restoreButton = JButton(CleanSettings.GUARD_TITLE).apply {
        addActionListener { if (CleanSettings.restoreGuard(project)) reset() }
    }

    override fun createComponent(): JComponent {
        var form = FormBuilder.createFormBuilder()
        sections.forEach { form = it.addTo(form) }
        form = form.addSeparator().addComponent(restoreButton)
        val built = form.addComponentFillVertically(JPanel(), 0).panel
        reset()
        settings.reload { if (!isModified()) reset() }
        return settingsScroller(built)
    }

    override fun isModified(): Boolean =
        shown?.let { s -> sections.any { section -> section.changedFields(s).any { it } } } ?: false

    override fun apply() {
        sections.forEach { it.validate() }
        val s = settings.state
        sections.forEach { it.apply(s) }
        settings.save()
        shown = s
        // The shield in every open chat is drawn from this, and the ⚙ menu mirrors the rules. A page that
        // saved without repainting them would leave a tab claiming protection it no longer has.
        JcefChatPanel.pushStateToAll()
        JcefChatPanel.pushSettingsMenuToAll()
    }

    override fun reset() {
        val s = settings.state
        sections.forEach { it.reset(s) }
        shown = s
    }

    override fun disposeUIResources() = sections.forEach { it.dispose() }
}
