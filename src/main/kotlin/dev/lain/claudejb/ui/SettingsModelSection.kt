package dev.lain.claudejb.ui

import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.FormBuilder
import dev.lain.claudejb.protocol.ModelInfo
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.SessionListener
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.ui.jcef.JcefModelLabels
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JList

/**
 * Model, effort, permission mode and the chat-wide toggles — the top of the Settings page.
 *
 * It owns the one genuinely stateful widget on the page: a model combo populated ASYNCHRONOUSLY from the
 * binary's catalogue while the dialog is already open. Everything about [shownModel] and [modelToSave] is
 * there because of that, and it is why this section is its own file rather than a handful of fields.
 *
 * [sessionOf] is a supplier rather than a session: the active chat can change between two openings of the
 * Settings dialog, and the listener has to follow it.
 */
internal class SettingsModelSection(private val sessionOf: () -> ClaudeSession) : SettingsSection {

    private val modelCombo = JComboBox<String>().apply { isEditable = true }
    private val effortCombo = JComboBox(ClaudeSession.EFFORT_LEVELS.toTypedArray())
    private val modeCombo = JComboBox(ClaudeSession.PERMISSION_MODES.toTypedArray())
    private val thinkingCheck = JBCheckBox("Extended thinking (adaptive — the model decides depth)")
    private val partialCheck = JBCheckBox("Stream partial messages (live token streaming)")
    private val restoreChatsCheck = JBCheckBox("Restore open chats on startup")
    private val reduceMotionCheck = JBCheckBox("Reduce motion (flatten chat animations)")

    /** Snapshot of the current session's model list, consulted by [modelRenderer] to pretty-print values. */
    private var currentModels: List<ModelInfo> = emptyList()

    /** SessionListener kept so the combo repopulates when `initialize` lands with fresh models. Disposed below. */
    private var modelListener: SessionListener? = null
    private var modelListenerSession: ClaudeSession? = null

    /** What [reset] last displayed in the model combo — the baseline [modelToSave] compares against. */
    private var shownModel: String = ""

    /** Renders a model value (e.g. `sonnet`) as its versioned human label ("Sonnet 5"); falls back to the raw
     *  value for custom/unknown entries. Shares the exact label logic the composer uses ([JcefModelLabels]), so the two
     *  selectors never disagree. Only affects the dropdown popup — the editable text field still shows the value
     *  (what we send to the binary), so power users can type a custom id. */
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
    }

    override fun reset(s: ClaudeSettings.State) {
        // A legacy install may still have the removed "default" alias persisted — show the concrete tier it now
        // resolves to, so the dialog never displays an option we no longer offer (saving then pins it).
        shownModel = if (s.model == ClaudeSession.RECOMMENDED_ALIAS) ClaudeSession.DEFAULT_MODEL else s.model
        modelCombo.selectedItem = shownModel
        effortCombo.selectedItem = s.effort
        modeCombo.selectedItem = s.permissionMode
        thinkingCheck.isSelected = s.thinkingTokens > 0
        partialCheck.isSelected = s.includePartialMessages
        restoreChatsCheck.isSelected = s.restoreOpenChatsOnStartup
        reduceMotionCheck.isSelected = s.reduceMotion
    }

    override fun apply(s: ClaudeSettings.State) {
        s.model = modelToSave(s.model)
        s.effort = effortText()
        s.permissionMode = modeText()
        s.thinkingTokens = if (thinkingCheck.isSelected) ClaudeSession.THINKING_ON else 0
        s.includePartialMessages = partialCheck.isSelected
        s.restoreOpenChatsOnStartup = restoreChatsCheck.isSelected
        s.reduceMotion = reduceMotionCheck.isSelected
    }

    override fun changedFields(s: ClaudeSettings.State): List<Boolean> = listOf(
        modelText() != s.model,
        effortText() != s.effort,
        modeText() != s.permissionMode,
        thinkingCheck.isSelected != (s.thinkingTokens > 0),
        partialCheck.isSelected != s.includePartialMessages,
        restoreChatsCheck.isSelected != s.restoreOpenChatsOnStartup,
        reduceMotionCheck.isSelected != s.reduceMotion,
    )

    override fun dispose() {
        modelListener?.let { lst -> modelListenerSession?.removeListener(lst) }
        modelListener = null
        modelListenerSession = null
    }

    /** Repopulate the model combo from the active session's `modelOptions()`, preserving the current selection
     *  (so an unsaved choice or a custom-typed value survives a refresh). Called once at create and again
     *  whenever the session reports fresh metadata (the binary's `initialize` lands asynchronously). */
    private fun rebuildModelCombo() {
        val opts = sessionOf().modelOptions()
        currentModels = opts
        val preserved = (modelCombo.editor?.item as? String)
            ?: (modelCombo.selectedItem as? String)
        // Drop the floating "default" alias — the concrete tier is what we offer (matches the composer list).
        val values = opts.map { it.value }.filter { it != ClaudeSession.RECOMMENDED_ALIAS }
        modelCombo.model = DefaultComboBoxModel(values.toTypedArray())
        if (!preserved.isNullOrBlank()) modelCombo.selectedItem = preserved
    }

    /** Subscribe to the active session so the combo refreshes when `initialize` returns real models. Idempotent
     *  per session; swaps if the active session changes between Settings dialog opens. */
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

    /**
     * The model to persist: [current], unless the user actually changed it in this dialog.
     *
     * **Saving whatever the widget happens to hold is not safe here, and that is not hypothetical.** The
     * combo is repopulated from the binary's catalogue, asynchronously, while the page is open — the
     * `initialize` reply lands seconds after the dialog does — and a freshly populated `DefaultComboBoxModel`
     * selects its own first entry. Any moment where that lands on the widget without [shownModel] following
     * turns a plain OK on an unrelated setting (the binary path, a security toggle) into a silent change of
     * model. A user's pinned Opus came back as `haiku`, which is simply what that catalogue lists first.
     *
     * Comparing against what [reset] PUT on screen — rather than trusting the widget — makes the write
     * deliberate by construction: no edit, no write. A blank field is never a choice either.
     */
    private fun modelToSave(current: String): String {
        val typed = modelText()
        if (typed.isBlank()) return current
        return if (typed == shownModel) current else typed
    }

    private fun effortText() = (effortCombo.selectedItem as? String).orEmpty()
    private fun modeText() = (modeCombo.selectedItem as? String) ?: "default"
}
