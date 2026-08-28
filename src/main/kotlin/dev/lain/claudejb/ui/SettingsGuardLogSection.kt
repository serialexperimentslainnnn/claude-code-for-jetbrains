package dev.lain.claudejb.ui

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.GuardAlertLog
import javax.swing.DefaultComboBoxModel

internal class SettingsGuardLogSection : SettingsSection {

    private val combo = ComboBox(DefaultComboBoxModel(RETENTIONS.map { it.days }.toTypedArray())).apply {
        renderer = labelRenderer { value -> RETENTIONS.firstOrNull { it.days == value }?.label }
    }

    override fun addTo(panel: Panel) {
        panel.group("Guard log") {
            row("Keep alerts for:") { cell(combo) }.rowComment(NOTE, MAX_LINE_LENGTH_WORD_WRAP)
        }
    }

    override fun reset(s: ClaudeSettings.State) {
        combo.selectedItem = RETENTIONS.firstOrNull { it.days == s.guardLogRetentionDays }?.days
            ?: GuardAlertLog.KEEP_UNTIL_FULL
    }

    override fun apply(s: ClaudeSettings.State) {
        s.guardLogRetentionDays = selected()
    }

    override fun changedFields(s: ClaudeSettings.State): List<Boolean> =
        listOf(selected() != s.guardLogRetentionDays)

    private fun selected(): Int = combo.selectedItem as? Int ?: GuardAlertLog.KEEP_UNTIL_FULL

    private data class Retention(val days: Int, val label: String)

    private companion object {

        const val NOTE =
            "An alert older than this is dropped the next time one is recorded. The log also stops at " +
                "${GuardAlertLog.MAX_ENTRIES} alerts for the whole project whatever this says, so a busy day " +
                "can still push an older alert out early. Alerts already dropped cannot be brought back."

        val RETENTIONS = listOf(
            Retention(1, "1 day"),
            Retention(7, "7 days"),
            Retention(30, "30 days"),
            Retention(90, "90 days"),
            Retention(365, "1 year"),
            Retention(GuardAlertLog.KEEP_UNTIL_FULL, "Until the log is full"),
        )
    }
}
