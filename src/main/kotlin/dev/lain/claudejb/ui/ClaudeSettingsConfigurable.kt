package dev.lain.claudejb.ui

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBFont
import com.intellij.ui.scale.JBUIScale
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.settings.ClaudeSettings
import java.awt.GridLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Settings page (Settings ▸ Claude Code) exposing the launch defaults graphically. Applies changes to
 * the live session and persists them via [ClaudeSettings]. The list-valued options (setting sources,
 * allowed/disallowed tools) are pick-from-checkboxes rather than free text, so there's nothing to type.
 */
class ClaudeSettingsConfigurable(private val project: Project) : Configurable {

    private val settings = ClaudeSettings.getInstance(project)
    private val session: ClaudeSession get() = ChatSessionManager.getInstance(project).activeOrCreate()

    private val modelCombo = JComboBox<String>().apply { isEditable = true }
    private val effortCombo = JComboBox(arrayOf("", *ClaudeSession.EFFORT_LEVELS.toTypedArray()))
    private val modeCombo = JComboBox(ClaudeSession.PERMISSION_MODES.toTypedArray())
    private val thinkingSpinner = JSpinner(SpinnerNumberModel(0, 0, 200_000, 1_000))
    private val partialCheck = JBCheckBox("Stream partial messages (live token streaming)")

    private val settingSourcesGroup = CheckboxGroup(ClaudeSession.SETTING_SOURCES, columns = 3)
    private val allowedToolsGroup = CheckboxGroup(ClaudeSession.BUILTIN_TOOLS, columns = 4)
    private val disallowedToolsGroup = CheckboxGroup(ClaudeSession.BUILTIN_TOOLS, columns = 4)

    private var panel: JPanel? = null

    override fun getDisplayName(): String = "Claude Code"

    override fun createComponent(): JComponent {
        modelCombo.model = DefaultComboBoxModel(arrayOf("", *session.models.map { it.value }.toTypedArray()))
        val built = FormBuilder.createFormBuilder()
            .addLabeledComponent("Model:", modelCombo)
            .addLabeledComponent("Effort:", effortCombo)
            .addLabeledComponent("Permission mode:", modeCombo)
            .addLabeledComponent("Thinking tokens (0 = off):", thinkingSpinner)
            .addComponent(partialCheck)
            .addSeparator()
            .addComponent(sectionLabel("Setting sources (none = don't pass --setting-sources)"))
            .addComponent(settingSourcesGroup.component)
            .addComponent(sectionLabel("Allowed tools (none = all tools allowed)"))
            .addComponent(allowedToolsGroup.component)
            .addComponent(sectionLabel("Disallowed tools (none = nothing blocked)"))
            .addComponent(disallowedToolsGroup.component)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        panel = built
        reset()
        return built
    }

    override fun isModified(): Boolean {
        val s = settings.state
        return modelText() != s.model ||
            effortText() != s.effort ||
            modeText() != s.permissionMode ||
            thinkingValue() != s.thinkingTokens ||
            partialCheck.isSelected != s.includePartialMessages ||
            csvSet(settingSourcesGroup.text()) != csvSet(s.settingSources) ||
            csvSet(allowedToolsGroup.text()) != csvSet(s.allowedTools) ||
            csvSet(disallowedToolsGroup.text()) != csvSet(s.disallowedTools)
    }

    override fun apply() {
        val s = settings.state
        s.model = modelText()
        s.effort = effortText()
        s.permissionMode = modeText()
        s.thinkingTokens = thinkingValue()
        s.includePartialMessages = partialCheck.isSelected
        s.settingSources = settingSourcesGroup.text()
        s.allowedTools = allowedToolsGroup.text()
        s.disallowedTools = disallowedToolsGroup.text()
        settings.applyTo(session)
    }

    override fun reset() {
        val s = settings.state
        modelCombo.selectedItem = s.model
        effortCombo.selectedItem = s.effort
        modeCombo.selectedItem = s.permissionMode
        thinkingSpinner.value = s.thinkingTokens
        partialCheck.isSelected = s.includePartialMessages
        settingSourcesGroup.setFrom(s.settingSources)
        allowedToolsGroup.setFrom(s.allowedTools)
        disallowedToolsGroup.setFrom(s.disallowedTools)
    }

    private fun modelText() = (modelCombo.editor.item as? String ?: modelCombo.selectedItem as? String).orEmpty().trim()
    private fun effortText() = (effortCombo.selectedItem as? String).orEmpty()
    private fun modeText() = (modeCombo.selectedItem as? String) ?: "default"
    private fun thinkingValue() = (thinkingSpinner.value as Number).toInt()

    private fun csvSet(s: String): Set<String> =
        s.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun sectionLabel(text: String) = JBLabel(text).apply { font = JBFont.medium().asBold() }

    /** A row/grid of checkboxes backed by a comma-separated value — the GUI form of a list option. */
    private class CheckboxGroup(options: List<String>, columns: Int) {
        private val boxes = LinkedHashMap<String, JBCheckBox>().apply {
            options.forEach { put(it, JBCheckBox(it)) }
        }
        val component: JComponent = JPanel(GridLayout(0, columns, JBUIScale.scale(8), JBUIScale.scale(2))).apply {
            boxes.values.forEach { add(it) }
        }

        fun text(): String = boxes.filterValues { it.isSelected }.keys.joinToString(",")

        fun setFrom(csv: String) {
            val selected = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            boxes.forEach { (name, box) -> box.isSelected = name in selected }
        }
    }
}
