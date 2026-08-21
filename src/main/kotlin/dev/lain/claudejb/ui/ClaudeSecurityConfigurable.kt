package dev.lain.claudejb.ui

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.panel
import dev.lain.claudejb.settings.ClaudeSettings
import javax.swing.JButton
import javax.swing.JComponent

class ClaudeSecurityConfigurable(private val project: Project) : Configurable {

    private val settings = ClaudeSettings.getInstance(project)

    private val masterSection = SettingsGuardMasterSection()
    private val rulesSection = SettingsSecuritySection(settings)
    private val logSection = SettingsGuardLogSection()

    private val sections: List<SettingsSection> = listOf(masterSection, rulesSection, logSection)

    override fun getDisplayName(): String = "Claude Code Security"

    private var shown: ClaudeSettings.State? = null

    private val restoreButton = JButton(CleanSettings.GUARD_TITLE).apply {
        addActionListener { if (CleanSettings.restoreGuard(project)) reset() }
    }

    override fun createComponent(): JComponent {
        val built = panel {
            sections.forEach { it.addTo(this) }
            separator()
            row { cell(restoreButton) }
        }
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
