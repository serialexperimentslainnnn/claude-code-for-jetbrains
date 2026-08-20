package dev.lain.claudejb.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.util.ui.FormBuilder
import dev.lain.claudejb.git.GitHistoryService
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.settings.ClaudeSettings
import javax.swing.JComponent
import javax.swing.JPanel

class ClaudeSettingsConfigurable(private val project: Project) : Configurable {

    private val settings = ClaudeSettings.getInstance(project)
    private val session: ClaudeSession get() = ChatSessionManager.getInstance(project).activeOrCreate()

    private val modelSection = SettingsModelSection { session }
    private val providerSection = SettingsProviderSection(settings)
    private val forgeSection = SettingsForgeSection { if (project.isDisposed) null else project.service<GitHistoryService>() }
    private val executableSection = SettingsExecutableSection()
    private val toolsSection = SettingsToolsSection(settings)
    private val mcpSection = SettingsMcpSection()
    private val advancedSection = SettingsAdvancedSection()

    private val sections: List<SettingsSection> = listOf(
        modelSection,
        providerSection,
        forgeSection,
        executableSection,
        toolsSection,
        mcpSection,
        advancedSection,
    )

    private val restoreButton = javax.swing.JButton(CleanSettings.PLUGIN_TITLE).apply {
        addActionListener { if (CleanSettings.restorePlugin(project)) reset() }
    }

    override fun getDisplayName(): String = "Claude Code"

    private var shown: ClaudeSettings.State? = null

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
        settings.applyTo(session)
        shown = s
    }

    override fun reset() {
        val s = settings.state
        sections.forEach { it.reset(s) }
        shown = s
    }

    override fun disposeUIResources() = sections.forEach { it.dispose() }
}
