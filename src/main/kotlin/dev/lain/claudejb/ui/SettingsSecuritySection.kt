package dev.lain.claudejb.ui

import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import dev.lain.claudejb.permission.SecurityCategory
import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.settings.ClaudeSettings
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel

internal class SettingsSecuritySection : SettingsSection {

    private val checks: Map<SecurityRule, JBCheckBox> = SecurityRule.entries.associateWith { rule ->
        JBCheckBox("${rule.label} (${rule.hint})")
    }

    private var unknownDisabled: List<String> = emptyList()

    private val extraDomainsArea = JBTextArea(EXTRA_DOMAIN_ROWS, 0).apply {
        lineWrap = false
        emptyText.text = "One domain per line, e.g. paste.example.com — added to the built-in list, never replacing it"
    }

    private val commandWhitelistArea = JBTextArea(WHITELIST_ROWS, 0).apply {
        lineWrap = false
        emptyText.text = "One full command per line, e.g. terraform destroy — matched exactly, and it can " +
            "never lift a credential, foreign-path, device or egress block"
    }

    private val restoreAllButton = JButton("Restore all protections").apply {
        addActionListener { checks.values.forEach { it.isSelected = true } }
    }

    private val categoryCards = JPanel(CardLayout())

    private val categoryCombo = JComboBox(SecurityCategory.entries.toTypedArray()).apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component = super.getListCellRendererComponent(
                list,
                (value as? SecurityCategory)?.label ?: value,
                index,
                isSelected,
                cellHasFocus,
            )
        }
        addActionListener { showSelectedCategory() }
    }

    init {
        SecurityCategory.entries.forEach { category ->
            categoryCards.add(cardFor(category), category.name)
        }
    }

    override fun addTo(form: FormBuilder): FormBuilder = form
        .addSeparator()
        .addComponent(sectionLabel("Security — deterministic tool-call lock, evaluated before every permission"))
        .addLabeledComponent("Category:", categoryCombo)
        .addComponent(categoryCards)
        .addComponent(restoreAllButton)
        .addComponent(sectionLabel("Always allow these exact commands (no card)"))
        .addComponent(JPanel(BorderLayout()).apply { add(commandWhitelistArea, BorderLayout.CENTER) })
        .addComponent(securityWarningLabel())

    private fun cardFor(category: SecurityCategory): JPanel {
        val card = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        SecurityRule.of(category).forEach { rule -> checks[rule]?.let { card.add(it) } }
        if (category == SecurityCategory.NETWORK_EGRESS) {
            card.add(sectionLabel("Extra blocked domains"))
            card.add(JPanel(BorderLayout()).apply { add(extraDomainsArea, BorderLayout.CENTER) })
        }
        return card
    }

    private fun showSelectedCategory() {
        val selected = categoryCombo.selectedItem as? SecurityCategory ?: SecurityCategory.entries.first()
        (categoryCards.layout as CardLayout).show(categoryCards, selected.name)
    }

    override fun reset(s: ClaudeSettings.State) {
        val stored = idsIn(s.disabledSecurityRules)
        checks.forEach { (rule, box) -> box.isSelected = rule.name !in stored }
        unknownDisabled = stored.filter { SecurityRule.from(it) == null }
        extraDomainsArea.text = s.securityExtraBlockedDomains
        commandWhitelistArea.text = s.securityCommandWhitelist
        if (categoryCombo.selectedItem == null) categoryCombo.selectedItem = SecurityCategory.entries.first()
        showSelectedCategory()
    }

    override fun apply(s: ClaudeSettings.State) {
        s.disabledSecurityRules = disabledCsv()
        s.securityExtraBlockedDomains = extraDomainsArea.text
        s.securityCommandWhitelist = commandWhitelistArea.text
    }

    override fun changedFields(s: ClaudeSettings.State): List<Boolean> =
        listOf(
            disabledCsv() != s.disabledSecurityRules,
            extraDomainsArea.text != s.securityExtraBlockedDomains,
            commandWhitelistArea.text != s.securityCommandWhitelist,
        )

    private fun disabledCsv(): String {
        val off = SecurityRule.entries.filter { checks[it]?.isSelected == false }.map { it.name }
        return SecurityRule.canonicalCsv(off + unknownDisabled)
    }

    private fun idsIn(csv: String): List<String> =
        csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun securityWarningLabel() = noteLabel(
        "⚠ <b>Security:</b> every rule is <b>ON by default</b>, and an empty list of exceptions is the plugin's " +
            "original hard lock exactly. Turning one OFF never allows a matching call silently — it only " +
            "downgrades an automatic block to a <b>permission card</b>, shown every time, for every caller " +
            "(including MCP servers and Skills), so you still decide case by case. Only disable a rule you " +
            "understand and specifically need — a project on a corporate network share, for example, needs the " +
            "network-mount rule off, not the whole lock. The <b>open project is exempt</b> from the location " +
            "rules, the temporary directory included: they are about what happens <i>outside</i> the surface you " +
            "are looking at. Two rules are deliberately not: a <b>dangerous command</b> and a <b>shell file " +
            "write</b> are judged wherever they run, because a <code>tee</code> or a <code>sed -i</code> has no " +
            "diff to review inside the project either.",
    )

    private companion object {
        const val EXTRA_DOMAIN_ROWS = 4

        const val WHITELIST_ROWS = 3
    }
}
