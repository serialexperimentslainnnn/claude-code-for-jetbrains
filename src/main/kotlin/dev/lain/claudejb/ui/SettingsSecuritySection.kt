package dev.lain.claudejb.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import dev.lain.claudejb.permission.SecurityCategory
import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.GuardMode
import dev.lain.claudejb.settings.GuardWhitelists
import dev.lain.claudejb.settings.SecuritySuspensions
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel

/**
 * The guard's rules, one **mode** each, and the three lists of commands that are allowed past them.
 *
 * Granular on two axes on purpose: a **category** can be moved to one mode in a single gesture, and every
 * individual **rule** inside it still has its own — the group is only a way to navigate the catalogue, and
 * the narrow thing is what people actually need to change. The same shape governs the whitelists: one
 * global, one per category, one per rule, asked narrowest-first by the guard.
 *
 * Enforcing and Permissive are the same two words the guard as a whole uses, and they mean the same thing at
 * both levels: refuse the match, or put it to the user as a card. Neither is a silent allow — the only two
 * of those are *Allow All* and a whitelisted command.
 */
internal class SettingsSecuritySection(private val settings: ClaudeSettings) : SettingsSection {

    private val modes: Map<SecurityRule, JComboBox<GuardMode>> =
        SecurityRule.entries.associateWith { modeCombo() }

    private var unknownPermissive: List<String> = emptyList()

    private var shownSuspended: Set<SecurityRule> = emptySet()

    private val globalWhitelistArea = area(WHITELIST_ROWS, "One full command per line — lifts any rule")

    private val categoryWhitelistAreas: Map<SecurityCategory, JBTextArea> =
        SecurityCategory.entries.associateWith { area(WHITELIST_ROWS, "One full command per line") }

    private val ruleWhitelistAreas: Map<SecurityCategory, JBTextArea> =
        SecurityCategory.entries.associateWith { area(WHITELIST_ROWS, "RULE_ID=full command, one per line") }

    private val extraDomainsArea = area(
        EXTRA_DOMAIN_ROWS,
        "One domain per line, e.g. paste.example.com — added to the built-in list, never replacing it",
    )

    private val extraGlobsArea = area(
        EXTRA_GLOB_ROWS,
        "One glob per line, e.g. **/secret.env — added to the built-in credential list, never replacing it",
    )

    private val cancelSuspensionsButton = JButton().apply {
        addActionListener { cancelSuspensions() }
    }

    private val categoryCards = JPanel(CardLayout())

    private val categoryCombo = JComboBox(SecurityCategory.entries.toTypedArray()).apply {
        renderer = labelRenderer { (it as? SecurityCategory)?.label }
        addActionListener { showSelectedCategory() }
    }

    init {
        SecurityCategory.entries.forEach { category ->
            categoryCards.add(cardFor(category), category.name)
        }
    }

    override fun addTo(form: FormBuilder): FormBuilder = form
        .addSeparator()
        .addComponent(sectionLabel("Rules — evaluated before every permission, in every mode"))
        .addLabeledComponent("Category:", categoryCombo)
        .addComponent(categoryCards)
        .addComponent(cancelSuspensionsButton)
        .addSeparator()
        .addComponent(sectionLabel("Whitelisted everywhere (applies to every rule)"))
        .addComponent(wrap(globalWhitelistArea))
        .addComponent(whitelistNote())
        .addSeparator()
        .addComponent(sectionLabel("Extra credential globs"))
        .addComponent(wrap(extraGlobsArea))
        .addComponent(securityWarningLabel())

    private fun cardFor(category: SecurityCategory): JPanel {
        val card = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        card.add(rowOf(bulkButton(category, GuardMode.ENFORCING), bulkButton(category, GuardMode.PERMISSIVE)))
        SecurityRule.of(category).forEach { rule -> card.add(ruleRow(rule)) }
        if (category == SecurityCategory.NETWORK_EGRESS) {
            card.add(sectionLabel("Extra blocked domains"))
            card.add(wrap(extraDomainsArea))
        }
        card.add(sectionLabel("Whitelisted for all of ${category.label}"))
        categoryWhitelistAreas[category]?.let { card.add(wrap(it)) }
        card.add(sectionLabel("Whitelisted for one rule of ${category.label}"))
        ruleWhitelistAreas[category]?.let { card.add(wrap(it)) }
        return card
    }

    private fun ruleRow(rule: SecurityRule) = JPanel(FlowLayout(FlowLayout.LEFT, HGAP, 0)).apply {
        modes[rule]?.let { add(it) }
        add(JBLabel("${rule.label} (${rule.hint})"))
    }

    private fun modeCombo() = JComboBox(GuardMode.entries.toTypedArray()).apply {
        renderer = labelRenderer { (it as? GuardMode)?.label }
    }

    private fun bulkButton(category: SecurityCategory, mode: GuardMode) =
        JButton("All ${mode.label}").apply {
            addActionListener { SecurityRule.of(category).forEach { modes[it]?.selectedItem = mode } }
        }

    private fun showSelectedCategory() {
        val selected = categoryCombo.selectedItem as? SecurityCategory ?: SecurityCategory.entries.first()
        (categoryCards.layout as CardLayout).show(categoryCards, selected.name)
    }

    override fun reset(s: ClaudeSettings.State) {
        val stored = idsIn(s.disabledSecurityRules)
        val now = System.currentTimeMillis()
        shownSuspended = SecuritySuspensions.active(s.securityRuleSuspensions, now) +
            SecuritySuspensions.sessionSuspended()
        modes.forEach { (rule, combo) ->
            combo.selectedItem = if (rule.name in stored) GuardMode.PERMISSIVE else GuardMode.ENFORCING
        }
        unknownPermissive = stored.filter { SecurityRule.from(it) == null }
        extraDomainsArea.text = s.securityExtraBlockedDomains
        extraGlobsArea.text = s.sensitiveExtraGlobs
        globalWhitelistArea.text = s.securityCommandWhitelist
        resetWhitelists(s)
        cancelSuspensionsButton.text = "End ${shownSuspended.size} temporary suspension(s)"
        cancelSuspensionsButton.isEnabled = shownSuspended.isNotEmpty()
        if (categoryCombo.selectedItem == null) categoryCombo.selectedItem = SecurityCategory.entries.first()
        showSelectedCategory()
    }

    private fun resetWhitelists(s: ClaudeSettings.State) {
        val byCategory = GuardWhitelists.byCategory(s.securityCategoryWhitelists)
        categoryWhitelistAreas.forEach { (category, box) ->
            box.text = byCategory[category].orEmpty().joinToString("\n")
        }
        val byRule = GuardWhitelists.byRule(s.securityRuleWhitelists)
        ruleWhitelistAreas.forEach { (category, box) ->
            box.text = SecurityRule.of(category)
                .flatMap { rule -> byRule[rule].orEmpty().map { "${rule.name}=$it" } }
                .joinToString("\n")
        }
    }

    override fun apply(s: ClaudeSettings.State) {
        s.disabledSecurityRules = permissiveCsv()
        s.securityExtraBlockedDomains = extraDomainsArea.text
        s.sensitiveExtraGlobs = extraGlobsArea.text
        s.securityCommandWhitelist = globalWhitelistArea.text
        s.securityCategoryWhitelists = categoryWhitelistCsv()
        s.securityRuleWhitelists = ruleWhitelistCsv()
    }

    override fun changedFields(s: ClaudeSettings.State): List<Boolean> =
        listOf(
            permissiveCsv() != s.disabledSecurityRules,
            extraDomainsArea.text != s.securityExtraBlockedDomains,
            extraGlobsArea.text != s.sensitiveExtraGlobs,
            globalWhitelistArea.text != s.securityCommandWhitelist,
            categoryWhitelistCsv() != s.securityCategoryWhitelists,
            ruleWhitelistCsv() != s.securityRuleWhitelists,
        )

    /**
     * Ends every timed and session suspension at once.
     *
     * Deliberately its own gesture rather than something a rule's mode combo does on the way past: a
     * suspension the user set and is watching count down must not end because this page was opened and OK
     * pressed without touching anything.
     */
    private fun cancelSuspensions() {
        if (shownSuspended.isEmpty()) return
        settings.update { state ->
            shownSuspended.forEach { rule ->
                state.securityRuleSuspensions =
                    SecuritySuspensions.without(state.securityRuleSuspensions, rule, System.currentTimeMillis())
                SecuritySuspensions.releaseSessionScoped(rule)
            }
        }
        shownSuspended = emptySet()
        cancelSuspensionsButton.text = "End 0 temporary suspension(s)"
        cancelSuspensionsButton.isEnabled = false
    }

    private fun categoryWhitelistCsv(): String =
        categoryWhitelistAreas.entries.flatMap { (category, box) ->
            GuardWhitelists.commands(box.text).map { "${category.name}=$it" }
        }.joinToString("\n")

    private fun ruleWhitelistCsv(): String =
        ruleWhitelistAreas.values.flatMap { GuardWhitelists.commands(it.text) }
            .filter { SecurityRule.from(it.substringBefore('=', "").trim()) != null }
            .joinToString("\n")

    private fun permissiveCsv(): String {
        val off = SecurityRule.entries.filter { modes[it]?.selectedItem == GuardMode.PERMISSIVE }.map { it.name }
        return SecurityRule.canonicalCsv(off + unknownPermissive)
    }

    private fun idsIn(csv: String): List<String> =
        csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun area(rows: Int, hint: String) = JBTextArea(rows, 0).apply {
        lineWrap = false
        emptyText.text = hint
    }

    private fun wrap(component: Component) = JPanel(BorderLayout()).apply { add(component, BorderLayout.CENTER) }

    private fun rowOf(vararg parts: Component) = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        parts.forEach { add(it) }
    }

    private fun whitelistNote() = noteLabel(
        "A whitelisted command runs with <b>no card and no block</b>, whatever mode its rule is in. The three " +
            "lists differ only in reach, and the guard asks the narrowest first: the rule that fired, then " +
            "that rule's category, then this one. Matching is on the <b>whole command</b>, de-obfuscated on " +
            "both sides — <code>terraform destroy</code> does not authorise " +
            "<code>terraform destroy &amp;&amp; rm -rf /</code>, and <code>t\"\"erraform destroy</code> cannot " +
            "sneak past an entry written normally. <b>Any rule can be whitelisted</b>, credential and " +
            "foreign-path rules included: an unliftable rule that fires on legitimate work leaves no way to " +
            "finish it, and which commands are permitted is your decision.",
    )

    private fun securityWarningLabel() = noteLabel(
        "⚠ <b>Security:</b> every rule is <b>Enforcing</b> by default, and an empty list of exceptions is the " +
            "plugin's original hard lock exactly. <b>Permissive</b> is never a silent allow — detection still " +
            "runs, and a match becomes a <b>permission card</b>, shown every time, for every caller " +
            "(including MCP servers and Skills), so you still decide case by case. The two things that do " +
            "allow silently are <b>Allow All</b> at the top of this page and a whitelisted command, and both " +
            "say so in the transcript when they act. Only relax a rule you understand and specifically need — " +
            "a project on a corporate network share, for example, needs the network-mount rule Permissive, " +
            "not the whole guard. The <b>open project is exempt</b> from the location rules, the temporary " +
            "directory included: they are about what happens <i>outside</i> the surface you are looking at. " +
            "Two rules are deliberately not: a <b>dangerous command</b> and a <b>shell file write</b> are " +
            "judged wherever they run, because a <code>tee</code> or a <code>sed -i</code> has no diff to " +
            "review inside the project either.",
    )

    private companion object {
        const val EXTRA_DOMAIN_ROWS = 4

        const val EXTRA_GLOB_ROWS = 3

        const val WHITELIST_ROWS = 3

        const val HGAP = 8
    }
}
