package dev.lain.claudejb.ui

import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
import dev.lain.claudejb.permission.SecurityCategory
import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.GuardMode
import dev.lain.claudejb.settings.SecuritySuspensions
import javax.swing.JButton
import javax.swing.JComboBox

internal class SettingsSecuritySection(private val settings: ClaudeSettings) : SettingsSection {

    private val modes: Map<SecurityRule, JComboBox<GuardMode>> =
        SecurityRule.entries.associateWith { modeCombo() }

    private var unknownPermissive: List<String> = emptyList()

    private var shownSuspended: Set<SecurityRule> = emptySet()

    private val whitelist = WhitelistTable()

    private val extraDomainsArea = area(EXTRA_DOMAIN_ROWS)

    private val extraGlobsArea = area(EXTRA_GLOB_ROWS)

    private val cancelSuspensionsButton = JButton().apply {
        addActionListener { cancelSuspensions() }
    }

    override fun addTo(panel: Panel) {
        panel.group("Rules — evaluated before every permission, in every mode", indent = false) {
            SecurityCategory.entries.forEach { category -> addCategory(this, category) }
            row { cell(cancelSuspensionsButton) }
        }
        panel.group("Whitelist — commands that run without a card, whatever mode their rule is in") {
            row { cell(whitelist.component).align(AlignX.FILL) }
                .rowComment(WHITELIST_NOTE, MAX_LINE_LENGTH_WORD_WRAP)
        }
        panel.collapsibleGroup("Advanced") {
            row("Extra credential globs:") { scrollCell(extraGlobsArea).align(AlignX.FILL) }
                .rowComment(
                    "One glob per line, e.g. <code>**/secret.env</code> — added to the built-in credential " +
                        "list, never replacing it.",
                    MAX_LINE_LENGTH_WORD_WRAP,
                )
            row { comment(SECURITY_NOTE, MAX_LINE_LENGTH_WORD_WRAP) }
        }
    }

    private fun addCategory(panel: Panel, category: SecurityCategory) {
        panel.collapsibleGroup(category.label) {
            row {
                cell(bulkButton(category, GuardMode.ENFORCING))
                cell(bulkButton(category, GuardMode.PERMISSIVE))
            }
            SecurityRule.of(category).forEach { rule ->
                row(rule.label) { modes[rule]?.let { cell(it) } }
                    .rowComment(rule.hint, MAX_LINE_LENGTH_WORD_WRAP)
            }
            if (category == SecurityCategory.NETWORK_EGRESS) {
                row("Extra blocked domains:") { scrollCell(extraDomainsArea).align(AlignX.FILL) }
                    .rowComment(
                        "One domain per line, e.g. <code>paste.example.com</code> — added to the built-in " +
                            "list, never replacing it.",
                        MAX_LINE_LENGTH_WORD_WRAP,
                    )
            }
        }
    }

    private fun modeCombo() = JComboBox(GuardMode.entries.toTypedArray()).apply {
        renderer = labelRenderer { (it as? GuardMode)?.label }
    }

    private fun bulkButton(category: SecurityCategory, mode: GuardMode) =
        JButton("All ${mode.label}").apply {
            addActionListener { SecurityRule.of(category).forEach { modes[it]?.selectedItem = mode } }
        }

    override fun reset(s: ClaudeSettings.State) {
        val stored = idsIn(s.disabledSecurityRules)
        val now = System.currentTimeMillis()
        shownSuspended = SecuritySuspensions.active(s.securityRuleSuspensions, now) +
            SecuritySuspensions.sessionSuspended(settings.scope.id)
        modes.forEach { (rule, combo) ->
            combo.selectedItem = if (rule.name in stored) GuardMode.PERMISSIVE else GuardMode.ENFORCING
        }
        unknownPermissive = stored.filter { SecurityRule.from(it) == null }
        extraDomainsArea.text = s.securityExtraBlockedDomains
        extraGlobsArea.text = s.sensitiveExtraGlobs
        whitelist.reset(s)
        cancelSuspensionsButton.text = "End ${shownSuspended.size} temporary suspension(s)"
        cancelSuspensionsButton.isEnabled = shownSuspended.isNotEmpty()
    }

    override fun apply(s: ClaudeSettings.State) {
        s.disabledSecurityRules = permissiveCsv()
        s.securityExtraBlockedDomains = extraDomainsArea.text
        s.sensitiveExtraGlobs = extraGlobsArea.text
        whitelist.apply(s)
    }

    override fun changedFields(s: ClaudeSettings.State): List<Boolean> =
        listOf(
            permissiveCsv() != s.disabledSecurityRules,
            extraDomainsArea.text != s.securityExtraBlockedDomains,
            extraGlobsArea.text != s.sensitiveExtraGlobs,
            whitelist.changed(s),
        )

    private fun cancelSuspensions() {
        if (shownSuspended.isEmpty()) return
        settings.update { state ->
            shownSuspended.forEach { rule ->
                state.securityRuleSuspensions =
                    SecuritySuspensions.without(state.securityRuleSuspensions, rule, System.currentTimeMillis())
                SecuritySuspensions.releaseSessionScoped(settings.scope.id, rule)
            }
        }
        shownSuspended = emptySet()
        cancelSuspensionsButton.text = "End 0 temporary suspension(s)"
        cancelSuspensionsButton.isEnabled = false
    }

    private fun permissiveCsv(): String {
        val off = SecurityRule.entries.filter { modes[it]?.selectedItem == GuardMode.PERMISSIVE }.map { it.name }
        return SecurityRule.canonicalCsv(off + unknownPermissive)
    }

    private fun idsIn(csv: String): List<String> =
        csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun area(rows: Int) = JBTextArea(rows, 0).apply { lineWrap = false }

    private companion object {
        const val EXTRA_DOMAIN_ROWS = 4

        const val EXTRA_GLOB_ROWS = 3

        const val WHITELIST_NOTE =
            "Pick which list you are editing — <b>All rules</b>, one <b>category</b>, or one <b>rule</b> — and " +
                "the commands below belong to it. The guard checks the narrowest first, so a permission can " +
                "always be traced to one entry. Matching is on the <b>whole command</b>: " +
                "<code>terraform destroy</code> does not authorise <code>terraform destroy &amp;&amp; rm -rf /</code>. " +
                "Both sides are de-obfuscated first, so an entry written normally still covers a spelling meant " +
                "to slip past it. <b>Any rule can be whitelisted</b>, credential and foreign-path rules included: " +
                "an unliftable rule that fires on legitimate work leaves no way to finish it."

        const val SECURITY_NOTE =
            "⚠ Every rule is <b>Enforcing</b> by default, and an empty list of exceptions is the plugin's " +
                "original hard lock exactly. <b>Permissive</b> is never a silent allow — detection still runs and " +
                "a match becomes a permission card, shown every time, for every caller including MCP servers and " +
                "Skills. The two things that do allow silently are <b>Allow All</b> and a whitelisted command, and " +
                "both say so in the transcript when they act. The <b>open project is exempt</b> from the location " +
                "rules, the temporary directory included: those are about what happens outside the surface you are " +
                "looking at. Two rules are deliberately not — a dangerous command and a shell file write are judged " +
                "wherever they run, because a <code>tee</code> or a <code>sed -i</code> has no diff to review " +
                "inside the project either."
    }
}
