package dev.lain.claudejb.view.settings.sections

import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.scale.JBUIScale
import dev.lain.claudejb.model.session.launch.IdeRule
import java.awt.GridLayout
import javax.swing.JComponent
import javax.swing.JPanel

internal class IdeRuleBoxes(rules: List<IdeRule>) {

    private val boxes: Map<IdeRule, JBCheckBox> = rules.associateWith { JBCheckBox(it.label) }

    val component: JComponent = JPanel(GridLayout(0, 1, 0, JBUIScale.scale(GAP_Y))).apply {
        isOpaque = false
        boxes.values.forEach { add(it) }
    }

    fun selected(): Set<IdeRule> = boxes.filterValues { it.isSelected }.keys

    fun setFrom(rules: Set<IdeRule>) = boxes.forEach { (rule, box) -> box.isSelected = rule in rules }

    fun checkAll() = boxes.values.forEach { it.isSelected = true }

    fun setEnabled(enabled: Boolean) = boxes.values.forEach { it.isEnabled = enabled }

    private companion object {
        const val GAP_Y = 2
    }
}
