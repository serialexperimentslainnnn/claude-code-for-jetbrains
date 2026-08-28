package dev.lain.claudejb.ui

import com.intellij.ui.CollectionListModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.ToolNaming
import dev.lain.claudejb.settings.ClaudeSettings
import javax.swing.JComponent

internal class SettingsToolsSection(private val settings: ClaudeSettings) : SettingsSection {

    private val settingSourcesGroup = CheckboxGroup(ClaudeSession.SETTING_SOURCES)
    private val allowedToolsGroup = CheckboxGroup(ToolNaming.BUILTIN_TOOLS)
    private val disallowedToolsGroup = CheckboxGroup(ToolNaming.BUILTIN_TOOLS)

    private val alwaysAllowModel = CollectionListModel<String>()
    private val alwaysAllowList = JBList(alwaysAllowModel).apply {
        emptyText.text = "No tools are auto-approved — every tool call shows a permission card."
        visibleRowCount = COMBO_VISIBLE_ROWS
    }

    override fun addTo(panel: Panel) {
        panel.collapsibleGroup("Tools") {
            row("Setting sources:") { cell(settingSourcesGroup.component).align(AlignY.TOP) }
                .rowComment("None ticked means <code>--setting-sources</code> is not passed at all.")
            row("Allowed tools:") { cell(allowedToolsGroup.component).align(AlignY.TOP) }
                .rowComment("None ticked means every tool is allowed.")
            row("Disallowed tools:") { cell(disallowedToolsGroup.component).align(AlignY.TOP) }
                .rowComment("None ticked means nothing is blocked.")
            row("Always-allowed:") { cell(alwaysAllowedComponent()).align(AlignX.FILL) }
                .rowComment(ALWAYS_ALLOW_NOTE, MAX_LINE_LENGTH_WORD_WRAP)
        }
    }

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

    private fun alwaysAllowedComponent(): JComponent =
        ToolbarDecorator.createDecorator(alwaysAllowList)
            .setRemoveAction { alwaysAllowList.selectedValuesList.forEach { alwaysAllowModel.remove(it) } }
            .disableAddAction()
            .disableUpDownActions()
            .createPanel()

    private companion object {
        const val COMBO_VISIBLE_ROWS = 4

        const val ALWAYS_ALLOW_NOTE =
            "⚠ Listed tools are auto-approved without a prompt, and this list is <b>global to every project</b>. " +
                "The Sensitive Guard still decides first: nothing here can bypass it. Select an entry and press " +
                "<b>Remove</b> to revoke it."
    }
}
