package dev.lain.claudejb.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.GuardMode
import dev.lain.claudejb.settings.SecuritySuspensions
import java.text.DateFormat
import java.util.Date
import javax.swing.JComboBox

/**
 * What the guard does, as **one** choice of three rather than a mode and a switch beside it.
 *
 * It was two controls, and the second was a checkbox nobody could read the meaning of. They are still two
 * different behaviours — Permissive asks, Allow All does not — but that is a difference between two values
 * of one question, not between two questions.
 */
internal class SettingsGuardMasterSection : SettingsSection {

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

    override fun addTo(form: FormBuilder): FormBuilder = form
        .addComponent(sectionLabel("Sensitive Guard — what happens when a rule matches"))
        .addLabeledComponent("Mode:", mode)
        .addComponent(explanation)
        .addLabeledComponent("Allow All for:", duration)
        .addComponent(expiry)
        .addComponent(
            noteLabel(
                "This is the mode for the guard as a whole. Each rule below has its own, and a rule set to " +
                    "<b>Permissive</b> stays Permissive while this says Enforcing. <b>Allow All</b> is the " +
                    "only setting that stops the guard deciding anything — it still evaluates, so the " +
                    "transcript records which rule went unenforced, but it blocks nothing and asks nothing. " +
                    "Every duration except <i>Forever</i> ends on its own, and the shield in the chat is the " +
                    "same control.",
            ),
        )

    override fun reset(s: ClaudeSettings.State) {
        val now = System.currentTimeMillis()
        shownAllowAll = SecuritySuspensions.guardSuspended(s, now)
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
            SecuritySuspensions.guardOn(s)
            s.guardMode = chosen.wire
            return
        }
        // Only when it was not Allow All a moment ago. Re-applying an untouched page must not restart the
        // clock on a suspension the user set an hour ago and has been watching count down.
        if (shownAllowAll) return
        val span = duration.selectedItem as? SecuritySuspensions.Duration ?: SecuritySuspensions.Duration.FOREVER
        SecuritySuspensions.guardOff(s, span, System.currentTimeMillis())
    }

    override fun changedFields(s: ClaudeSettings.State): List<Boolean> {
        val now = System.currentTimeMillis()
        val shown = if (SecuritySuspensions.guardSuspended(s, now)) {
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
}
