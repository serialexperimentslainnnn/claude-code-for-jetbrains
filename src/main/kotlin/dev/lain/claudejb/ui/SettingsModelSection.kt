package dev.lain.claudejb.ui

import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.FormBuilder
import dev.lain.claudejb.protocol.ModelInfo
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.SessionListener
import dev.lain.claudejb.session.WorkloadWindow
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.ui.jcef.JcefModelLabels
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JList

internal class SettingsModelSection(private val sessionOf: () -> ClaudeSession) : SettingsSection {

    private val modelCombo = JComboBox<String>().apply { isEditable = true }
    private val effortCombo = JComboBox(ClaudeSession.EFFORT_LEVELS.toTypedArray())
    private val modeCombo = JComboBox(ClaudeSession.PERMISSION_MODES.toTypedArray())
    private val thinkingCheck = JBCheckBox("Extended thinking (adaptive — the model decides depth)")
    private val partialCheck = JBCheckBox("Stream partial messages (live token streaming)")
    private val restoreChatsCheck = JBCheckBox("Restore open chats on startup")
    private val reduceMotionCheck = JBCheckBox("Reduce motion (flatten chat animations)")

    private val workloadWindowCombo = JComboBox(WorkloadWindow.WINDOW_MINUTES.toTypedArray()).apply {
        renderer = object : SimpleListCellRenderer<Int>() {
            override fun customize(list: JList<out Int>, value: Int?, index: Int, selected: Boolean, focused: Boolean) {
                text = value?.let { WorkloadWindow.label(it) }.orEmpty()
            }
        }
    }

    private var currentModels: List<ModelInfo> = emptyList()

    private var modelListener: SessionListener? = null
    private var modelListenerSession: ClaudeSession? = null

    private var shownModel: String = ""

    private val modelRenderer = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): java.awt.Component {
            val raw = (value as? String).orEmpty()
            val pretty = currentModels.firstOrNull { it.value == raw }?.let { JcefModelLabels.modelDisplayLabel(it) }
                ?: raw
            return super.getListCellRendererComponent(list, pretty, index, isSelected, cellHasFocus)
        }
    }

    override fun addTo(form: FormBuilder): FormBuilder {
        modelCombo.renderer = modelRenderer
        modeCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val label = dev.lain.claudejb.session.PermissionMode.labelFor(value as? String)
                return super.getListCellRendererComponent(list, label, index, isSelected, cellHasFocus)
            }
        }
        rebuildModelCombo()
        ensureModelListener()
        return form
            .addLabeledComponent("Model:", modelCombo)
            .addLabeledComponent("Effort:", effortCombo)
            .addLabeledComponent("Permission mode:", modeCombo)
            .addComponent(thinkingCheck)
            .addComponent(partialCheck)
            .addComponent(restoreChatsCheck)
            .addComponent(reduceMotionCheck)
            .addLabeledComponent("Keep finished workloads listed for:", workloadWindowCombo)
    }

    override fun reset(s: ClaudeSettings.State) {
        shownModel = if (s.model == ClaudeSession.RECOMMENDED_ALIAS) ClaudeSession.DEFAULT_MODEL else s.model
        modelCombo.selectedItem = shownModel
        effortCombo.selectedItem = s.effort
        modeCombo.selectedItem = s.permissionMode
        thinkingCheck.isSelected = s.thinkingTokens > 0
        partialCheck.isSelected = s.includePartialMessages
        restoreChatsCheck.isSelected = s.restoreOpenChatsOnStartup
        reduceMotionCheck.isSelected = s.reduceMotion
        workloadWindowCombo.selectedItem =
            s.workloadWindowMinutes.takeIf { it in WorkloadWindow.WINDOW_MINUTES } ?: WorkloadWindow.DEFAULT_MINUTES
    }

    override fun apply(s: ClaudeSettings.State) {
        s.model = modelToSave(s.model)
        s.effort = effortText()
        s.permissionMode = modeText()
        s.thinkingTokens = if (thinkingCheck.isSelected) ClaudeSession.THINKING_ON else 0
        s.includePartialMessages = partialCheck.isSelected
        s.restoreOpenChatsOnStartup = restoreChatsCheck.isSelected
        s.reduceMotion = reduceMotionCheck.isSelected
        s.workloadWindowMinutes = workloadWindowMinutes()
    }

    private fun workloadWindowMinutes(): Int =
        (workloadWindowCombo.selectedItem as? Int) ?: WorkloadWindow.DEFAULT_MINUTES

    override fun changedFields(s: ClaudeSettings.State): List<Boolean> = listOf(
        modelText() != s.model,
        effortText() != s.effort,
        modeText() != s.permissionMode,
        thinkingCheck.isSelected != (s.thinkingTokens > 0),
        partialCheck.isSelected != s.includePartialMessages,
        restoreChatsCheck.isSelected != s.restoreOpenChatsOnStartup,
        reduceMotionCheck.isSelected != s.reduceMotion,
        workloadWindowMinutes() != s.workloadWindowMinutes,
    )

    override fun dispose() {
        modelListener?.let { lst -> modelListenerSession?.removeListener(lst) }
        modelListener = null
        modelListenerSession = null
    }

    private fun rebuildModelCombo() {
        val opts = sessionOf().modelOptions()
        currentModels = opts
        val preserved = (modelCombo.editor?.item as? String)
            ?: (modelCombo.selectedItem as? String)
        val values = opts.map { it.value }.filter { it != ClaudeSession.RECOMMENDED_ALIAS }
        modelCombo.model = DefaultComboBoxModel(values.toTypedArray())
        if (!preserved.isNullOrBlank()) modelCombo.selectedItem = preserved
    }

    private fun ensureModelListener() {
        val s = sessionOf()
        if (modelListenerSession === s) return
        modelListener?.let { lst -> modelListenerSession?.removeListener(lst) }
        val lst = object : SessionListener {
            override fun onMetadataChanged() {
                javax.swing.SwingUtilities.invokeLater { rebuildModelCombo() }
            }
        }
        s.addListener(lst)
        modelListener = lst
        modelListenerSession = s
    }

    private fun modelText() = (modelCombo.editor.item as? String ?: modelCombo.selectedItem as? String).orEmpty().trim()

    private fun modelToSave(current: String): String {
        val typed = modelText()
        if (typed.isBlank()) return current
        return if (typed == shownModel) current else typed
    }

    private fun effortText() = (effortCombo.selectedItem as? String).orEmpty()
    private fun modeText() = (modeCombo.selectedItem as? String) ?: "default"
}
