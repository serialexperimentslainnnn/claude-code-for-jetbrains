package dev.lain.claudejb.ui

import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import dev.lain.claudejb.settings.ClaudeSettings
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

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

    override fun addTo(panel: Panel) {
        panel.collapsibleGroup("Advanced") {
            row("Max turns:") { cell(maxTurnsSpinner) }
            row("Max budget (USD):") { cell(maxBudgetSpinner) }
            row("Fallback model:") { cell(fallbackModelField).align(AlignX.FILL) }
                .rowComment("Zero or blank means the flag is omitted entirely.")
            row("Additional directories:") { scrollCell(addDirsArea).align(AlignX.FILL) }
                .rowComment("One absolute path per line; blank means the project root only.")
            row("Betas:") { cell(betasField).align(AlignX.FILL) }
        }
    }

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
        const val MAX_TURNS = 1_000

        const val MAX_BUDGET_USD = 10_000.0
        const val BUDGET_STEP_USD = 0.5

        const val ADD_DIRS_ROWS = 3
    }
}
