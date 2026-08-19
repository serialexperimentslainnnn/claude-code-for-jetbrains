package dev.lain.claudejb.ui

import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBFont
import dev.lain.claudejb.settings.ClaudeSettings
import java.awt.GridLayout
import javax.swing.JComponent
import javax.swing.JPanel

internal interface SettingsSection {

    fun addTo(form: FormBuilder): FormBuilder

    fun reset(s: ClaudeSettings.State)

    fun apply(s: ClaudeSettings.State)

    fun changedFields(s: ClaudeSettings.State): List<Boolean>

    @Throws(ConfigurationException::class)
    fun validate() = Unit

    fun dispose() = Unit
}

internal const val SETTINGS_FORM_WIDTH = 600

internal fun sectionLabel(text: String) = JBLabel(text).apply { font = JBFont.medium().asBold() }

internal fun noteLabel(bodyHtml: String) = JBLabel(
    "<html><body style='width:${SETTINGS_FORM_WIDTH}px'>$bodyHtml</body></html>",
).apply { font = JBFont.small() }

internal fun csvSet(s: String): Set<String> =
    s.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

internal class CheckboxGroup(options: List<String>, columns: Int) {
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
