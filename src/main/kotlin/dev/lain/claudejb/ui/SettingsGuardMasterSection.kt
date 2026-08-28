package dev.lain.claudejb.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.GuardMode
import dev.lain.claudejb.settings.SecuritySuspensions
import java.text.DateFormat
import java.util.Date
import javax.swing.JComboBox

internal class SettingsGuardMasterSection(private val settings: ClaudeSettings) : SettingsSection {

    private val scope get() = settings.scope.id

    private val mode = JComboBox(GuardMode.entries.toTypedArray()).apply {
        renderer = labelRenderer { (it as? GuardMode)?.label }
        addActionListener { syncEnabled() }
    }

    private val duration = JComboBox(SecuritySuspensions.Duration.entries.toTypedArray()).apply {
        renderer = labelRenderer { (it as? SecuritySuspensions.Duration)?.label }
        selectedItem = SecuritySuspensions.Duration.FOREVER
    }

    private val explanation = JBLabel()

    private val expiry = JBLabel()

    private var shownAllowAll = false

    override fun addTo(panel: Panel) {
        panel.group("Sensitive Guard") {
            row("Mode:") { cell(mode) }
            row("") { cell(explanation) }
            row("Allow All for:") { cell(duration) }
            row("") { cell(expiry) }
                .rowComment(GUARD_MODE_NOTE, MAX_LINE_LENGTH_WORD_WRAP)
        }
    }

    override fun reset(s: ClaudeSettings.State) {
        val now = System.currentTimeMillis()
        shownAllowAll = SecuritySuspensions.guardSuspended(scope, s, now)
        mode.selectedItem = when {
            shownAllowAll -> GuardMode.ALLOW_ALL
            else -> GuardMode.from(s.guardMode) ?: GuardMode.DEFAULT
        }
        expiry.text = expiryText(SecuritySuspensions.guardSuspendedUntil(s, now))
        syncEnabled()
    }

    override fun apply(s: ClaudeSettings.State) {
        val chosen = selected()
        if (chosen != GuardMode.ALLOW_ALL) {
            SecuritySuspensions.guardOn(scope, s)
            s.guardMode = chosen.wire
            return
        }
        if (shownAllowAll) return
        val span = duration.selectedItem as? SecuritySuspensions.Duration ?: SecuritySuspensions.Duration.FOREVER
        SecuritySuspensions.guardOff(scope, s, span, System.currentTimeMillis())
    }

    override fun changedFields(s: ClaudeSettings.State): List<Boolean> {
        val now = System.currentTimeMillis()
        val shown = if (SecuritySuspensions.guardSuspended(scope, s, now)) {
            GuardMode.ALLOW_ALL
        } else {
            GuardMode.from(s.guardMode) ?: GuardMode.DEFAULT
        }
        return listOf(selected() != shown)
    }

    private fun selected() = mode.selectedItem as? GuardMode ?: GuardMode.DEFAULT

    private fun syncEnabled() {
        val allowAll = selected() == GuardMode.ALLOW_ALL
        duration.isEnabled = allowAll && !shownAllowAll
        explanation.text = selected().summary
        expiry.isVisible = expiry.text.isNotEmpty()
    }

    private fun expiryText(until: Long?): String {
        if (until == null) return ""
        val at = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(until))
        return "Allow All ends at $at, and the guard decides again from then on."
    }

    private companion object {
        const val GUARD_MODE_NOTE =
            "This is the guard as a whole. Each rule below keeps its own mode, and one set to <b>Permissive</b> " +
                "stays Permissive while this says Enforcing. <b>Allow All</b> is the only setting that stops the " +
                "guard deciding: it still evaluates, so the transcript names the rule that went unenforced, but " +
                "it blocks nothing and asks nothing. Every duration except <i>Forever</i> ends on its own, and " +
                "the shield in the chat is the same control."
    }
}
