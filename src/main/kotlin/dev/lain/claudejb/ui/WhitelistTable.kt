package dev.lain.claudejb.ui

import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import dev.lain.claudejb.permission.SecurityCategory
import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.GuardWhitelists
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

/**
 * How far a whitelisted command reaches — the thing the user picks, in the words they already read elsewhere.
 *
 * Stored as three separate fields because the guard asks them narrowest-first, but that is a storage detail.
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
        /** Widest first, then each category with its own rules under it, so the list reads as a narrowing. */
        val CHOICES: List<WhitelistScope> = buildList {
            add(Everywhere)
            SecurityCategory.entries.forEach { category ->
                add(OfCategory(category))
                SecurityRule.of(category).forEach { add(OfRule(it)) }
            }
        }
    }
}

/**
 * The commands allowed past the guard: pick a scope, see and edit that scope's list.
 *
 * The scope is one dropdown above the list, not a column inside it — the same shape the rule catalogue above
 * uses for its categories, and the reason is the same. A row that carries its own scope makes every row a
 * separate decision to read; a selector above makes the question "which list am I editing" once, and the
 * list underneath is then just commands.
 *
 * It defaults to **All rules**, which is the list most people want and the only one that needs no
 * explanation.
 */
internal class WhitelistTable {

    private val entries = linkedMapOf<WhitelistScope, MutableList<String>>()

    private var current: WhitelistScope = WhitelistScope.Everywhere

    /** AbstractTableModel keeps its fire* methods protected, so the visible half is declared here. */
    private inner class CommandsModel : AbstractTableModel() {
        override fun getRowCount() = commandsFor(current).size
        override fun getColumnCount() = 1
        override fun getColumnName(column: Int) = "Command"
        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = true
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = commandsFor(current)[rowIndex]

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            commandsFor(current)[rowIndex] = value?.toString().orEmpty()
            fireTableRowsUpdated(rowIndex, rowIndex)
        }

        fun refresh() = fireTableDataChanged()
        fun inserted(at: Int) = fireTableRowsInserted(at, at)
        fun deleted(at: Int) = fireTableRowsDeleted(at, at)
    }

    private val model = CommandsModel()

    private val table = JBTable(model).apply {
        setShowGrid(false)
        emptyText.text = "Nothing whitelisted here — this rule decides on its own"
    }

    private val scope = JComboBox(WhitelistScope.CHOICES.toTypedArray()).apply {
        renderer = labelRenderer { (it as? WhitelistScope)?.label }
        selectedItem = WhitelistScope.Everywhere
        addActionListener {
            stopEditing()
            current = selectedItem as? WhitelistScope ?: WhitelistScope.Everywhere
            table.emptyText.text = emptyTextFor(current)
            // Qualified: inside a JComboBox apply block, `model` is the combo's own ComboBoxModel.
            this@WhitelistTable.model.refresh()
        }
    }

    val component: JComponent = JPanel(BorderLayout()).apply {
        add(
            JPanel(FlowLayout(FlowLayout.LEFT, HGAP, 0)).apply {
                add(JBLabel("Applies to:"))
                add(scope)
            },
            BorderLayout.NORTH,
        )
        add(
            ToolbarDecorator.createDecorator(table)
                .setAddAction { add() }
                .setRemoveAction { remove() }
                .disableUpDownActions()
                .createPanel(),
            BorderLayout.CENTER,
        )
    }

    private fun commandsFor(at: WhitelistScope) = entries.getOrPut(at) { mutableListOf() }

    private fun add() {
        stopEditing()
        val commands = commandsFor(current)
        commands.add("")
        model.inserted(commands.lastIndex)
        table.editCellAt(commands.lastIndex, 0)
        table.editorComponent?.requestFocusInWindow()
    }

    private fun remove() {
        stopEditing()
        val commands = commandsFor(current)
        table.selectedRows.sortedDescending().forEach { at ->
            if (at in commands.indices) {
                commands.removeAt(at)
                model.deleted(at)
            }
        }
    }

    /** A half-typed cell counts: OK gets pressed with the caret still in the field more often than not. */
    private fun stopEditing() {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
    }

    private fun emptyTextFor(at: WhitelistScope) = when (at) {
        is WhitelistScope.Everywhere -> "Nothing is whitelisted for every rule"
        else -> "Nothing whitelisted for ${at.label.substringAfter('·').trim()}"
    }

    fun reset(s: ClaudeSettings.State) {
        entries.clear()
        commandsFor(WhitelistScope.Everywhere).addAll(GuardWhitelists.commands(s.securityCommandWhitelist))
        GuardWhitelists.byCategory(s.securityCategoryWhitelists).forEach { (category, commands) ->
            commandsFor(WhitelistScope.OfCategory(category)).addAll(commands)
        }
        GuardWhitelists.byRule(s.securityRuleWhitelists).forEach { (rule, commands) ->
            commandsFor(WhitelistScope.OfRule(rule)).addAll(commands)
        }
        model.fireTableDataChanged()
    }

    fun apply(s: ClaudeSettings.State) {
        stopEditing()
        s.securityCommandWhitelist = commandsFor(WhitelistScope.Everywhere)
            .filter { it.isNotBlank() }
            .joinToString("\n") { it.trim() }
        s.securityCategoryWhitelists = keyed { (it as? WhitelistScope.OfCategory)?.category?.name }
        s.securityRuleWhitelists = keyed { (it as? WhitelistScope.OfRule)?.rule?.name }
    }

    private fun keyed(idOf: (WhitelistScope) -> String?): String =
        entries.entries.flatMap { (at, commands) ->
            val id = idOf(at) ?: return@flatMap emptyList()
            commands.filter { it.isNotBlank() }.map { "$id=${it.trim()}" }
        }.joinToString("\n")

    fun changed(s: ClaudeSettings.State): Boolean {
        val current = ClaudeSettings.State()
        apply(current)
        return current.securityCommandWhitelist != s.securityCommandWhitelist ||
            current.securityCategoryWhitelists != s.securityCategoryWhitelists ||
            current.securityRuleWhitelists != s.securityRuleWhitelists
    }

    private companion object {
        const val HGAP = 8
    }
}
