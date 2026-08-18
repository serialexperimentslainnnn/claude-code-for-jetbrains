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
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel

/**
 * The deterministic tool-call lock's per-rule switches (see `permission/SensitiveGuard.kt`), one checkbox per
 * [SecurityRule], grouped behind a [SecurityCategory] selector.
 *
 * Every rule is ON by default and OFF downgrades an automatic block to a permission card — never to a silent
 * allow, which is why the note spells it out on the page itself.
 *
 * ### Why a combo and a `CardLayout`, and not a `panel {}`
 * There is no Kotlin UI DSL anywhere in this repository (`com.intellij.ui.dsl` appears nowhere in `src`), and a
 * `collapsibleGroup` would also have to reproduce the `reset`/`apply`/`changedFields` triple that
 * [SettingsSection] keeps deliberately manual. So the categories are drawn with what the page already uses: a
 * `JComboBox` of categories over one card per category. **Every checkbox exists whatever is on screen** — the
 * selector is purely a view concern — so this section keeps the shape its contract asks for: one typed
 * comparison per setting in [changedFields], twelve of them instead of seven.
 *
 * ### The stored value is the DISABLED set, and this page must not prune it
 * An id the stored CSV names but this build does not know can only come from a newer version, and rebuilding the
 * field from the checkboxes alone would silently drop it — i.e. re-enable, on the next OK, a rule the user turned
 * off in a later IDE. [unknownDisabled] carries those through untouched. The order written is canonical
 * ([SecurityRule.entries] order, unknown ids last) because the field is a SET and two spellings of the same set
 * would otherwise read as a modification.
 */
internal class SettingsSecuritySection : SettingsSection {

    /** One checkbox per rule, all of them alive regardless of which card is showing. */
    private val checks: Map<SecurityRule, JBCheckBox> = SecurityRule.entries.associateWith { rule ->
        JBCheckBox("${rule.label} (${rule.hint})")
    }

    /** Rule ids the stored value names and this build cannot resolve — preserved verbatim (see the class doc). */
    private var unknownDisabled: List<String> = emptyList()

    private val extraDomainsArea = JBTextArea(EXTRA_DOMAIN_ROWS, 0).apply {
        lineWrap = false
        emptyText.text = "One domain per line, e.g. paste.example.com — added to the built-in list, never replacing it"
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
        .addComponent(securityWarningLabel())

    /** One category's rules, plus whatever else that category owns — today only the egress domain list. */
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
        if (categoryCombo.selectedItem == null) categoryCombo.selectedItem = SecurityCategory.entries.first()
        showSelectedCategory()
    }

    override fun apply(s: ClaudeSettings.State) {
        s.disabledSecurityRules = disabledCsv()
        s.securityExtraBlockedDomains = extraDomainsArea.text
    }

    override fun changedFields(s: ClaudeSettings.State): List<Boolean> =
        listOf(
            disabledCsv() != s.disabledSecurityRules,
            extraDomainsArea.text != s.securityExtraBlockedDomains,
        )

    /** What is unchecked, in the one canonical spelling both writers of this field use. */
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
        /** Tall enough to show a handful of domains without turning the page into a text editor. */
        const val EXTRA_DOMAIN_ROWS = 4
    }
}
