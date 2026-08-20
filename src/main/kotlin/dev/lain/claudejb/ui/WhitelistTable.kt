package dev.lain.claudejb.ui

import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import dev.lain.claudejb.permission.SecurityCategory
import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.GuardWhitelists
import javax.swing.DefaultCellEditor
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.table.AbstractTableModel

/**
 * How far a whitelisted command reaches — the thing the user picks, in the words they already read elsewhere.
 *
 * Stored as three separate fields because the guard asks them narrowest-first, but that is a storage detail:
 * on screen it is one list, and each row says where it applies.
 */
internal sealed interface WhitelistScope {

    val label: String

    object Everywhere : WhitelistScope {
        override val label = "All rules"
    }

    data class OfCategory(val category: SecurityCategory) : WhitelistScope {
        override val label get() = "Category · ${category.label}"
    }

    data class OfRule(val rule: SecurityRule) : WhitelistScope {
        override val label get() = "Rule · ${rule.label}"
    }

    companion object {
        /** Everything the user can choose, widest first, so the narrow options read as the refinement. */
        val CHOICES: List<WhitelistScope> = buildList {
            add(Everywhere)
            SecurityCategory.entries.forEach { category ->
                add(OfCategory(category))
                SecurityRule.of(category).forEach { add(OfRule(it)) }
            }
        }
    }
}

private class WhitelistRow(var scope: WhitelistScope, var command: String)

/**
 * The commands allowed past the guard, as a list you add rows to.
 *
 * It replaces three free-text boxes, one of which asked for `RULE_ID=command` — a format whose left-hand
 * side existed nowhere the user could read it. The rule is a dropdown now, and picking one is the whole
 * difference between "this command is fine here" and "this command is fine everywhere".
 */
internal class WhitelistTable {

    private val rows = mutableListOf<WhitelistRow>()

    private val model = object : AbstractTableModel() {
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 2
        override fun getColumnName(column: Int) = if (column == 0) "Applies to" else "Command"
        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = true
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0) WhitelistScope::class.java else String::class.java

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any =
            if (columnIndex == 0) rows[rowIndex].scope else rows[rowIndex].command

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex == 0) {
                rows[rowIndex].scope = value as? WhitelistScope ?: WhitelistScope.Everywhere
            } else {
                rows[rowIndex].command = value?.toString().orEmpty()
            }
            fireTableRowsUpdated(rowIndex, rowIndex)
        }
    }

    private val table = JBTable(model).apply {
        emptyText.text = "No command is whitelisted — every rule decides on its own"
        setShowGrid(false)
        columnModel.getColumn(0).apply {
            preferredWidth = SCOPE_WIDTH
            cellEditor = DefaultCellEditor(
                JComboBox(WhitelistScope.CHOICES.toTypedArray()).apply {
                    renderer = labelRenderer { (it as? WhitelistScope)?.label }
                },
            )
            cellRenderer = object : javax.swing.table.DefaultTableCellRenderer() {
                override fun setValue(value: Any?) {
                    text = (value as? WhitelistScope)?.label ?: value?.toString().orEmpty()
                }
            }
        }
    }

    val component: JComponent = ToolbarDecorator.createDecorator(table)
        .setAddAction { add() }
        .setRemoveAction { remove() }
        .disableUpDownActions()
        .createPanel()

    private fun add() {
        stopEditing()
        rows.add(WhitelistRow(WhitelistScope.Everywhere, ""))
        model.fireTableRowsInserted(rows.lastIndex, rows.lastIndex)
        table.editCellAt(rows.lastIndex, 1)
    }

    private fun remove() {
        stopEditing()
        table.selectedRows.sortedDescending().forEach { at ->
            if (at in rows.indices) {
                rows.removeAt(at)
                model.fireTableRowsDeleted(at, at)
            }
        }
    }

    /** Any half-typed cell counts: OK is pressed with the caret still in the field more often than not. */
    private fun stopEditing() {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
    }

    fun reset(s: ClaudeSettings.State) {
        rows.clear()
        GuardWhitelists.commands(s.securityCommandWhitelist).forEach {
            rows.add(WhitelistRow(WhitelistScope.Everywhere, it))
        }
        GuardWhitelists.byCategory(s.securityCategoryWhitelists).forEach { (category, commands) ->
            commands.forEach { rows.add(WhitelistRow(WhitelistScope.OfCategory(category), it)) }
        }
        GuardWhitelists.byRule(s.securityRuleWhitelists).forEach { (rule, commands) ->
            commands.forEach { rows.add(WhitelistRow(WhitelistScope.OfRule(rule), it)) }
        }
        model.fireTableDataChanged()
    }

    fun apply(s: ClaudeSettings.State) {
        stopEditing()
        val kept = rows.filter { it.command.isNotBlank() }
        s.securityCommandWhitelist = kept.filter { it.scope is WhitelistScope.Everywhere }
            .joinToString("\n") { it.command.trim() }
        s.securityCategoryWhitelists = kept.mapNotNull { row ->
            (row.scope as? WhitelistScope.OfCategory)?.let { "${it.category.name}=${row.command.trim()}" }
        }.joinToString("\n")
        s.securityRuleWhitelists = kept.mapNotNull { row ->
            (row.scope as? WhitelistScope.OfRule)?.let { "${it.rule.name}=${row.command.trim()}" }
        }.joinToString("\n")
    }

    fun changed(s: ClaudeSettings.State): Boolean {
        val current = ClaudeSettings.State()
        apply(current)
        return current.securityCommandWhitelist != s.securityCommandWhitelist ||
            current.securityCategoryWhitelists != s.securityCategoryWhitelists ||
            current.securityRuleWhitelists != s.securityRuleWhitelists
    }

    private companion object {
        const val SCOPE_WIDTH = 260
    }
}
