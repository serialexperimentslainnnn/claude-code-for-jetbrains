package dev.lain.claudejb.view.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.panel
import dev.lain.claudejb.controller.commands.CleanSettings
import dev.lain.claudejb.controller.session.ChatSessionManager
import dev.lain.claudejb.controller.session.ClaudeSession
import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.view.settings.sections.SettingsAdvancedSection
import dev.lain.claudejb.view.settings.sections.SettingsExecutableSection
import dev.lain.claudejb.view.settings.sections.SettingsIdeMcpSection
import dev.lain.claudejb.view.settings.sections.SettingsMcpSection
import dev.lain.claudejb.view.settings.sections.SettingsModelSection
import dev.lain.claudejb.view.settings.sections.SettingsProviderSection
import dev.lain.claudejb.view.settings.sections.SettingsToolsSection
import dev.lain.claudejb.view.settings.sections.SettingsTransferSection
import javax.swing.JComponent

class ClaudeSettingsConfigurable(private val project: Project) : Configurable {

    private val settings = ClaudeSettings.getInstance(project)
    private val session: ClaudeSession get() = ChatSessionManager.getInstance(project).activeOrCreate()

    private val modelSection = SettingsModelSection { session }
    private val providerSection = SettingsProviderSection(settings)
    private val executableSection = SettingsExecutableSection()
    private val toolsSection = SettingsToolsSection(settings)
    private val ideMcpSection = SettingsIdeMcpSection()
    private val mcpSection = SettingsMcpSection()
    private val advancedSection = SettingsAdvancedSection()
    private val transferSection = SettingsTransferSection(project) { reset() }

    private val sections: List<SettingsSection> = listOf(
        modelSection,
        providerSection,
        executableSection,
        toolsSection,
        ideMcpSection,
        mcpSection,
        advancedSection,
        transferSection,
    )

    private val restoreButton = javax.swing.JButton(CleanSettings.PLUGIN_TITLE).apply {
        addActionListener { if (CleanSettings.restorePlugin(project)) reset() }
    }

    override fun getDisplayName(): String = "Claude Code"

    private var shown: ClaudeSettings.State? = null

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
        ChatSessionManager.getInstance(project).adoptSettings()
        shown = s
    }

    override fun reset() {
        val s = settings.state
        sections.forEach { it.reset(s) }
        shown = s
    }

    override fun disposeUIResources() = sections.forEach { it.dispose() }
}
