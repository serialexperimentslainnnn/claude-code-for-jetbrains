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
import javax.swing.DefaultComboBoxModel
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
        override val label get() = category.label
    }

    data class OfRule(val rule: SecurityRule) : WhitelistScope {
        override val label get() = rule.label
    }
}

/**
 * The commands allowed past the guard: pick a scope, see and edit that scope's list.
 *
 * The scope is **two** dropdowns above the list — category, then rule within it — each carrying its own
 * *All*. One flat list of every category and every rule interleaved was thirty-odd entries deep and made
 * "which of these is a category and which is a rule" something you had to read the prefix to know; two
 * combos make it something the shape of the control tells you. The three reaches map exactly:
 *
 * - *All rules* → the global list.
 * - a category, rule left at *All in this category* → that category's list.
 * - a category and a rule → that rule's list.
 *
 * It opens on the global list, which is the one most people want and the only one that needs no
 * explanation.
 */
internal class WhitelistTable {

    private val entries = linkedMapOf<WhitelistScope, MutableList<String>>()

    private var current: WhitelistScope = WhitelistScope.Everywhere

    /** Rebuilding the rule combo fires its own listener; this stops that being read as a scope change. */
    private var syncing = false

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
        emptyText.text = "Nothing is whitelisted for every rule"
    }

    /** `null` is *All rules*: the global list, which is not any one category's. */
    private val categoryCombo = JComboBox(
        (listOf(null) + SecurityCategory.entries).toTypedArray(),
    ).apply {
        renderer = labelRenderer { (it as? SecurityCategory)?.label ?: ALL_RULES }
        addActionListener { onCategoryChosen() }
    }

    /** `null` is *All in this category*, or *All rules* again when no category is chosen. */
    private val ruleCombo = JComboBox<SecurityRule?>().apply {
        renderer = labelRenderer { (it as? SecurityRule)?.label ?: allLabel() }
        addActionListener { if (!syncing) onScopeChanged() }
    }

    val component: JComponent = JPanel(BorderLayout()).apply {
        add(
            JPanel(FlowLayout(FlowLayout.LEFT, HGAP, 0)).apply {
                add(JBLabel("Applies to:"))
                add(categoryCombo)
                add(JBLabel("Rule:"))
                add(ruleCombo)
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

    init {
        rebuildRules()
    }

    private fun selectedCategory() = categoryCombo.selectedItem as? SecurityCategory

    private fun selectedRule() = ruleCombo.selectedItem as? SecurityRule

    private fun allLabel() = if (selectedCategory() == null) ALL_RULES else ALL_IN_CATEGORY

    private fun onCategoryChosen() {
        rebuildRules()
        onScopeChanged()
    }

    /** The rules on offer are the chosen category's, and none at all when the scope is every rule. */
    private fun rebuildRules() {
        syncing = true
        try {
            val category = selectedCategory()
            val options = listOf(null) + (category?.let { SecurityRule.of(it) } ?: emptyList())
            ruleCombo.model = DefaultComboBoxModel(options.toTypedArray())
            ruleCombo.selectedItem = null
            ruleCombo.isEnabled = category != null
        } finally {
            syncing = false
        }
    }

    private fun onScopeChanged() {
        stopEditing()
        val category = selectedCategory()
        val rule = selectedRule()
        current = when {
            category == null -> WhitelistScope.Everywhere
            rule == null -> WhitelistScope.OfCategory(category)
            else -> WhitelistScope.OfRule(rule)
        }
        table.emptyText.text = emptyTextFor(current)
        model.refresh()
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
        else -> "Nothing whitelisted for ${at.label}"
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
        model.refresh()
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
        const val ALL_RULES = "All rules"
        const val ALL_IN_CATEGORY = "All in this category"
    }
}
