package dev.lain.claudejb.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import dev.lain.claudejb.settings.ClaudeSettings
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/** The advanced launch flags, all with a neutral default that omits the flag entirely (0 / blank). */
internal class SettingsAdvancedSection : SettingsSection {

    private val maxTurnsSpinner = JSpinner(SpinnerNumberModel(0, 0, MAX_TURNS, 1))
    private val maxBudgetSpinner = JSpinner(SpinnerNumberModel(0.0, 0.0, MAX_BUDGET_USD, BUDGET_STEP_USD))
    private val fallbackModelField = JBTextField().apply {
        emptyText.text = "Optional model to retry with on overload (e.g. sonnet); blank = none"
    }
    private val addDirsArea = JBTextArea(ADD_DIRS_ROWS, 0).apply {
        emptyText.text = "Extra accessible directories, one absolute path per line; blank = project root only"
    }
    private val betasField = JBTextField().apply {
        emptyText.text = "Comma-separated beta feature flags; blank = none"
    }

    override fun addTo(form: FormBuilder): FormBuilder = form
        .addSeparator()
        .addComponent(sectionLabel("Advanced launch (0 / blank = flag omitted)"))
        .addLabeledComponent("Max turns:", maxTurnsSpinner)
        .addLabeledComponent("Max budget (USD):", maxBudgetSpinner)
        .addLabeledComponent("Fallback model:", fallbackModelField)
        .addComponent(sectionLabel("Additional directories (one path per line)"))
        .addComponent(JBScrollPane(addDirsArea))
        .addLabeledComponent("Betas:", betasField)

    override fun reset(s: ClaudeSettings.State) {
        maxTurnsSpinner.value = s.maxTurns
        maxBudgetSpinner.value = s.maxBudgetUsd
        fallbackModelField.text = s.fallbackModel
        addDirsArea.text = s.addDirs
        betasField.text = s.betas
    }

    override fun apply(s: ClaudeSettings.State) {
        s.maxTurns = maxTurnsValue()
        s.maxBudgetUsd = maxBudgetValue()
        s.fallbackModel = fallbackModelField.text.trim()
        s.addDirs = addDirsArea.text
        s.betas = betasField.text.trim()
    }

    override fun changedFields(s: ClaudeSettings.State): List<Boolean> = listOf(
        maxTurnsValue() != s.maxTurns,
        maxBudgetValue() != s.maxBudgetUsd,
        fallbackModelField.text.trim() != s.fallbackModel,
        addDirsArea.text != s.addDirs,
        betasField.text.trim() != s.betas,
    )

    private fun maxTurnsValue() = (maxTurnsSpinner.value as Number).toInt()
    private fun maxBudgetValue() = (maxBudgetSpinner.value as Number).toDouble()

    private companion object {
        /** Upper bound of the turn cap spinner. */
        const val MAX_TURNS = 1_000

        /** Upper bound of the per-turn budget spinner, in USD, and the step it moves in. */
        const val MAX_BUDGET_USD = 10_000.0
        const val BUDGET_STEP_USD = 0.5

        /** Visible rows of the additional-directories area, i.e. how tall it is before scrolling. */
        const val ADD_DIRS_ROWS = 3
    }
}
