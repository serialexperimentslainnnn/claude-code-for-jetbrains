package dev.lain.claudejb.ui

import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.FormBuilder
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.GuardMode
import dev.lain.claudejb.settings.SecuritySuspensions
import java.text.DateFormat
import java.util.Date
import javax.swing.JButton
import javax.swing.JComboBox

/**
 * The two controls that sit above every rule, and they are **two axes, not one**.
 *
 * *Allow All* decides whether the guard judges anything at all; **Mode** decides what a match means while it
 * is judging. Off by nothing and Enforcing by default, which together are the plugin's original hard lock.
 */
internal class SettingsGuardMasterSection : SettingsSection {

    private val allowAll = JBCheckBox("Allow All — let every matching call through without a card").apply {
        addActionListener { syncEnabled() }
    }

    private val duration = JComboBox(SecuritySuspensions.Duration.entries.toTypedArray()).apply {
        renderer = labelRenderer { (it as? SecuritySuspensions.Duration)?.label }
        selectedItem = SecuritySuspensions.Duration.FOREVER
    }

    private val mode = JComboBox(GuardMode.entries.toTypedArray()).apply {
        renderer = labelRenderer { (it as? GuardMode)?.label }
    }

    private val enforceNow = JButton("Enforce now").apply {
        addActionListener {
            allowAll.isSelected = false
            syncEnabled()
        }
    }

    private var shownAllowAll = false

    private var shownUntil: Long? = null

    override fun addTo(form: FormBuilder): FormBuilder = form
        .addComponent(sectionLabel("Sensitive Guard"))
        .addLabeledComponent("Mode:", mode)
        .addComponent(modeNote())
        .addComponent(allowAll)
        .addLabeledComponent("Allow All for:", duration)
        .addComponent(enforceNow)
        .addComponent(allowAllNote())

    override fun reset(s: ClaudeSettings.State) {
        val now = System.currentTimeMillis()
        shownAllowAll = SecuritySuspensions.guardSuspended(s, now)
        shownUntil = SecuritySuspensions.guardSuspendedUntil(s, now)
        allowAll.isSelected = shownAllowAll
        mode.selectedItem = GuardMode.from(s.guardMode) ?: GuardMode.DEFAULT
        syncEnabled()
    }

    override fun apply(s: ClaudeSettings.State) {
        s.guardMode = (mode.selectedItem as? GuardMode ?: GuardMode.DEFAULT).wire
        if (!allowAll.isSelected) {
            SecuritySuspensions.guardOn(s)
            return
        }
        // Only when it was OFF a moment ago. Re-applying an untouched page must not silently restart the
        // clock on an Allow All the user set an hour ago and has been watching count down.
        if (shownAllowAll) return
        val chosen = duration.selectedItem as? SecuritySuspensions.Duration ?: SecuritySuspensions.Duration.FOREVER
        SecuritySuspensions.guardOff(s, chosen, System.currentTimeMillis())
    }

    override fun changedFields(s: ClaudeSettings.State): List<Boolean> = listOf(
        allowAll.isSelected != SecuritySuspensions.guardSuspended(s, System.currentTimeMillis()),
        (mode.selectedItem as? GuardMode ?: GuardMode.DEFAULT).wire != s.guardMode,
    )

    private fun syncEnabled() {
        duration.isEnabled = allowAll.isSelected
        enforceNow.isEnabled = allowAll.isSelected
        mode.isEnabled = !allowAll.isSelected
    }

    private fun modeNote() = noteLabel(
        "<b>Enforcing</b> refuses a matching call outright. <b>Permissive</b> puts it to you as a card " +
            "instead, every time — detection still runs and nothing is allowed silently. This is the default " +
            "for every rule below; a rule set to Permissive on its own overrides Enforcing here, and " +
            "Permissive here puts the whole catalogue in Permissive whatever the individual rules say.",
    )

    private fun allowAllNote() = noteLabel(
        "⚠ <b>Allow All is the only setting that stops the guard deciding anything.</b> While it is on, a " +
            "matching call runs with no card and no block — a credential read, a <code>terraform destroy</code> " +
            "or a path outside the project alike. The guard still evaluates, so the transcript says which rule " +
            "went unenforced each time, but it stops nothing. Every duration except <i>Forever</i> ends on its " +
            "own: it is re-checked on every call, so nothing has to be remembered or cleaned up. The shield in " +
            "the chat's button row is the same switch, and it is unlit whenever this is on." + expiryNote(),
    )

    private fun expiryNote(): String {
        val until = shownUntil ?: return ""
        val at = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(until))
        return " <br><b>Currently on until $at.</b>"
    }
}
