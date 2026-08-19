package dev.lain.claudejb.ui

import com.intellij.ui.CollectionListModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.util.ui.FormBuilder
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.ToolNaming
import dev.lain.claudejb.settings.ClaudeSettings
import javax.swing.JComponent

internal class SettingsToolsSection(private val settings: ClaudeSettings) : SettingsSection {

    private val settingSourcesGroup = CheckboxGroup(ClaudeSession.SETTING_SOURCES, columns = 3)
    private val allowedToolsGroup = CheckboxGroup(ToolNaming.BUILTIN_TOOLS, columns = 4)
    private val disallowedToolsGroup = CheckboxGroup(ToolNaming.BUILTIN_TOOLS, columns = 4)

    private val alwaysAllowModel = CollectionListModel<String>()
    private val alwaysAllowList = JBList(alwaysAllowModel).apply {
        emptyText.text = "No tools are auto-approved — every tool call shows a permission card."
        visibleRowCount = COMBO_VISIBLE_ROWS
    }

    override fun addTo(form: FormBuilder): FormBuilder = form
        .addSeparator()
        .addComponent(sectionLabel("Setting sources (none = don't pass --setting-sources)"))
        .addComponent(settingSourcesGroup.component)
        .addComponent(sectionLabel("Allowed tools (none = all tools allowed)"))
        .addComponent(allowedToolsGroup.component)
        .addComponent(sectionLabel("Disallowed tools (none = nothing blocked)"))
        .addComponent(disallowedToolsGroup.component)
        .addComponent(sectionLabel("Always-allowed tools"))
        .addComponent(alwaysAllowedWarningLabel())
        .addComponent(alwaysAllowedComponent())

    override fun reset(s: ClaudeSettings.State) {
        settingSourcesGroup.setFrom(s.settingSources)
        allowedToolsGroup.setFrom(s.allowedTools)
        disallowedToolsGroup.setFrom(s.disallowedTools)
        alwaysAllowModel.replaceAll(settings.alwaysAllow.all())
    }

    override fun apply(s: ClaudeSettings.State) {
        s.settingSources = settingSourcesGroup.text()
        s.allowedTools = allowedToolsGroup.text()
        s.disallowedTools = disallowedToolsGroup.text()
        settings.alwaysAllow.replace(alwaysAllowModel.items.toList())
    }

    override fun changedFields(s: ClaudeSettings.State): List<Boolean> = listOf(
        csvSet(settingSourcesGroup.text()) != csvSet(s.settingSources),
        csvSet(allowedToolsGroup.text()) != csvSet(s.allowedTools),
        csvSet(disallowedToolsGroup.text()) != csvSet(s.disallowedTools),
        alwaysAllowModel.items != settings.alwaysAllow.all(),
    )

    private fun alwaysAllowedWarningLabel() = noteLabel(
        "⚠ <b>Security:</b> listed tools are auto-approved without a prompt <b>in every project</b> — the " +
            "settings are global since 5.5.0 (writes still stay within each project's own root, and the " +
            "sensitive-data lock above still applies). Select an entry and click <b>Remove</b> to revoke it.",
    )

    private fun alwaysAllowedComponent(): JComponent =
        ToolbarDecorator.createDecorator(alwaysAllowList)
            .setRemoveAction { alwaysAllowList.selectedValuesList.forEach { alwaysAllowModel.remove(it) } }
            .disableAddAction()
            .disableUpDownActions()
            .createPanel()

    private companion object {
        const val COMBO_VISIBLE_ROWS = 4
    }
}
