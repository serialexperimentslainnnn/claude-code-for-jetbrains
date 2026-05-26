package dev.lain.claudejb.ui

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.settings.ClaudeSettings
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Settings page (Settings ▸ Claude Code) exposing the launch defaults graphically. Applies changes to
 * the live session and persists them via [ClaudeSettings].
 */
class ClaudeSettingsConfigurable(private val project: Project) : Configurable {

    private val settings = ClaudeSettings.getInstance(project)
    private val session: ClaudeSession get() = ChatSessionManager.getInstance(project).activeOrCreate()

    private val modelCombo = JComboBox<String>().apply { isEditable = true }
    private val effortCombo = JComboBox(arrayOf("", *ClaudeSession.EFFORT_LEVELS.toTypedArray()))
    private val modeCombo = JComboBox(ClaudeSession.PERMISSION_MODES.toTypedArray())
    private val thinkingSpinner = JSpinner(SpinnerNumberModel(0, 0, 200_000, 1_000))
    private val partialCheck = JBCheckBox("Stream partial messages (live token streaming)")
    private val settingSourcesCheck = JBCheckBox("Custom setting sources:")
    private val settingSourcesField = JBTextField()
    private val allowedToolsCheck = JBCheckBox("Restrict allowed tools:")
    private val allowedToolsField = JBTextField()
    private val disallowedToolsCheck = JBCheckBox("Restrict disallowed tools:")
    private val disallowedToolsField = JBTextField()

    private var panel: JPanel? = null

    override fun getDisplayName(): String = "Claude Code"

    override fun createComponent(): JComponent {
        modelCombo.model = DefaultComboBoxModel(arrayOf("", *session.models.map { it.value }.toTypedArray()))
        settingSourcesCheck.addActionListener { settingSourcesField.isEnabled = settingSourcesCheck.isSelected }
        allowedToolsCheck.addActionListener { allowedToolsField.isEnabled = allowedToolsCheck.isSelected }
        disallowedToolsCheck.addActionListener { disallowedToolsField.isEnabled = disallowedToolsCheck.isSelected }
        val built = FormBuilder.createFormBuilder()
            .addLabeledComponent("Model:", modelCombo)
            .addLabeledComponent("Effort:", effortCombo)
            .addLabeledComponent("Permission mode:", modeCombo)
            .addLabeledComponent("Thinking tokens (0 = off):", thinkingSpinner)
            .addComponent(settingSourcesCheck)
            .addComponent(settingSourcesField)
            .addComponent(allowedToolsCheck)
            .addComponent(allowedToolsField)
            .addComponent(disallowedToolsCheck)
            .addComponent(disallowedToolsField)
            .addComponent(partialCheck)
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
            settingSourcesText() != s.settingSources ||
            allowedToolsText() != s.allowedTools ||
            disallowedToolsText() != s.disallowedTools
    }

    override fun apply() {
        val s = settings.state
        s.model = modelText()
        s.effort = effortText()
        s.permissionMode = modeText()
        s.thinkingTokens = thinkingValue()
        s.includePartialMessages = partialCheck.isSelected
        s.settingSources = settingSourcesText()
        s.allowedTools = allowedToolsText()
        s.disallowedTools = disallowedToolsText()
        settings.applyTo(session)
    }

    override fun reset() {
        val s = settings.state
        modelCombo.selectedItem = s.model
        effortCombo.selectedItem = s.effort
        modeCombo.selectedItem = s.permissionMode
        thinkingSpinner.value = s.thinkingTokens
        partialCheck.isSelected = s.includePartialMessages
        settingSourcesCheck.isSelected = s.settingSources.isNotBlank()
        settingSourcesField.text = s.settingSources
        settingSourcesField.isEnabled = settingSourcesCheck.isSelected
        allowedToolsCheck.isSelected = s.allowedTools.isNotBlank()
        allowedToolsField.text = s.allowedTools
        allowedToolsField.isEnabled = allowedToolsCheck.isSelected
        disallowedToolsCheck.isSelected = s.disallowedTools.isNotBlank()
        disallowedToolsField.text = s.disallowedTools
        disallowedToolsField.isEnabled = disallowedToolsCheck.isSelected
    }

    private fun modelText() = (modelCombo.editor.item as? String ?: modelCombo.selectedItem as? String).orEmpty().trim()
    private fun effortText() = (effortCombo.selectedItem as? String).orEmpty()
    private fun modeText() = (modeCombo.selectedItem as? String) ?: "default"
    private fun thinkingValue() = (thinkingSpinner.value as Number).toInt()
    private fun settingSourcesText() = if (settingSourcesCheck.isSelected) settingSourcesField.text.trim() else ""
    private fun allowedToolsText() = if (allowedToolsCheck.isSelected) allowedToolsField.text.trim() else ""
    private fun disallowedToolsText() = if (disallowedToolsCheck.isSelected) disallowedToolsField.text.trim() else ""
}
