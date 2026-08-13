package dev.lain.claudejb.ui

import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBFont
import dev.lain.claudejb.settings.ClaudeSettings
import java.awt.GridLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * One section of the Settings page (Settings ▸ Claude Code), owning its own widgets.
 *
 * [addTo], [reset], [apply] and [changedFields] are deliberately the same list of fields read four times: a
 * section that forgets one of them is the classic "the field never registers as modified" bug, and inside one
 * subject it is visible at a glance instead of buried among thirty others. Every persisted field must be
 * claimed by exactly one section — `ClaudeSettingsConfigurableHeadlessTest` pins that, since a field owned by
 * nobody fails silently.
 */
internal interface SettingsSection {

    /** Appends this section's rows to the page's single [FormBuilder], in page order. */
    fun addTo(form: FormBuilder): FormBuilder

    /** Loads the persisted values onto the widgets. */
    fun reset(s: ClaudeSettings.State)

    /**
     * Writes the widgets onto the settings document. **Never persists** — the page saves once, at the end, so
     * a section cannot leave half the form written when a later one throws.
     */
    fun apply(s: ClaudeSettings.State)

    /**
     * One entry per setting: does the form differ from what is saved?
     *
     * A list rather than a 30-term `||` chain. Each entry is still a plain typed comparison, so the compiler
     * keeps checking that both sides are the same type — which a `List<Pair<Any?, Any?>>` would have thrown
     * away, and which is exactly the mistake that makes a field silently never register as modified.
     */
    fun changedFields(s: ClaudeSettings.State): List<Boolean>

    /** Rejects input the settings document must never receive. Runs for EVERY section before any [apply]. */
    @Throws(ConfigurationException::class)
    fun validate() = Unit

    /** Releases anything the section subscribed to (see `Configurable.disposeUIResources`). */
    fun dispose() = Unit
}

/** Fixed content width (CSS px) the form and its wrapping HTML notes are bounded to, so a wide monitor
 *  doesn't stretch the page edge-to-edge. */
internal const val SETTINGS_FORM_WIDTH = 600

internal fun sectionLabel(text: String) = JBLabel(text).apply { font = JBFont.medium().asBold() }

/**
 * A small, **width-bounded** HTML note. The `width:` on the body forces the text to wrap at
 * [SETTINGS_FORM_WIDTH] instead of demanding a single huge line — without it these one-line HTML labels blew
 * the form's preferred width up to the whole monitor (the Settings dialog opened enormous). [bodyHtml] is the
 * inner markup (no <html>/<body>).
 */
internal fun noteLabel(bodyHtml: String) = JBLabel(
    "<html><body style='width:${SETTINGS_FORM_WIDTH}px'>$bodyHtml</body></html>",
).apply { font = JBFont.small() }

internal fun csvSet(s: String): Set<String> =
    s.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

/** A row/grid of checkboxes backed by a comma-separated value — the GUI form of a list option. */
internal class CheckboxGroup(options: List<String>, columns: Int) {
    private val boxes = LinkedHashMap<String, JBCheckBox>().apply {
        options.forEach { put(it, JBCheckBox(it)) }
    }
    val component: JComponent = JPanel(GridLayout(0, columns, JBUIScale.scale(8), JBUIScale.scale(2))).apply {
        boxes.values.forEach { add(it) }
    }

    fun text(): String = boxes.filterValues { it.isSelected }.keys.joinToString(",")

    fun setFrom(csv: String) {
        val selected = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        boxes.forEach { (name, box) -> box.isSelected = name in selected }
    }
}
