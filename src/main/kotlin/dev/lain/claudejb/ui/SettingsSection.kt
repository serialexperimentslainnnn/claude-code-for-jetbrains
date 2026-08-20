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

internal interface SettingsSection {

    fun addTo(form: FormBuilder): FormBuilder

    fun reset(s: ClaudeSettings.State)

    fun apply(s: ClaudeSettings.State)

    fun changedFields(s: ClaudeSettings.State): List<Boolean>

    @Throws(ConfigurationException::class)
    fun validate() = Unit

    fun dispose() = Unit
}

/** The widest a paragraph is allowed to get before it stops being readable, and the narrowest it may go. */
private const val NOTE_MAX_WIDTH = 900
private const val NOTE_MIN_WIDTH = 320
private const val NOTE_SIDE_MARGIN = 28

/** Where a note keeps its own text, so the page can re-render it at whatever width it ends up with. */
private const val NOTE_BODY = "claudejb.noteBody"

/**
 * The scroll pane every Claude settings page is wrapped in.
 *
 * The form fills the width rather than being pinned left, and there is no horizontal scrollbar: a settings
 * page that scrolls sideways is a page whose text has nowhere to wrap. What keeps a paragraph readable on a
 * wide monitor is [NOTE_MAX_WIDTH], not a fixed-size form.
 *
 * Swing HTML does not reflow on its own — a `<body style='width:Npx'>` is measured once and stays that
 * width — so the notes are re-rendered here whenever the viewport changes size. Doing it in one place is
 * what stops every section having its own opinion about how wide the page is.
 */
internal fun settingsScroller(built: JComponent): JComponent {
    val holder = JPanel(java.awt.BorderLayout()).apply {
        isOpaque = false
        border = com.intellij.util.ui.JBUI.Borders.empty(0, 0, 0, JBUIScale.scale(12))
        add(built, java.awt.BorderLayout.NORTH)
    }
    return com.intellij.ui.components.JBScrollPane(holder).apply {
        border = com.intellij.util.ui.JBUI.Borders.empty()
        viewport.isOpaque = false
        isOpaque = false
        verticalScrollBar.unitIncrement = JBUIScale.scale(16)
        horizontalScrollBarPolicy = com.intellij.ui.components.JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        viewport.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                relayoutNotes(built, viewport.width)
            }
        })
    }
}

/** Re-renders every note under [root] at the width the page actually has. */
private fun relayoutNotes(root: JComponent, viewportWidth: Int) {
    val width = (viewportWidth - JBUIScale.scale(NOTE_SIDE_MARGIN))
        .coerceIn(JBUIScale.scale(NOTE_MIN_WIDTH), JBUIScale.scale(NOTE_MAX_WIDTH))
    notesUnder(root).forEach { note ->
        val body = note.getClientProperty(NOTE_BODY) as? String ?: return@forEach
        note.text = "<html><body style='width:${width}px'>$body</body></html>"
    }
    root.revalidate()
}

private fun notesUnder(component: java.awt.Component): List<JBLabel> = when {
    component is JBLabel && component.getClientProperty(NOTE_BODY) != null -> listOf(component)
    component is java.awt.Container -> component.components.flatMap { notesUnder(it) }
    else -> emptyList()
}

internal fun sectionLabel(text: String) = JBLabel(text).apply { font = JBFont.medium().asBold() }

/**
 * A small, wrapping paragraph.
 *
 * It keeps its own body text in a client property because Swing's HTML is laid out once: re-wrapping means
 * re-rendering, and re-rendering means still having the source. [settingsScroller] is what calls back.
 */
internal fun noteLabel(bodyHtml: String) = JBLabel(
    "<html><body style='width:${JBUIScale.scale(NOTE_MAX_WIDTH)}px'>$bodyHtml</body></html>",
).apply {
    font = JBFont.small()
    putClientProperty(NOTE_BODY, bodyHtml)
}

/**
 * A combo renderer that shows an enum's own label instead of its constant name.
 *
 * Shared because the alternative is one anonymous `DefaultListCellRenderer` per combo, and the ones on the
 * security page all want the same thing: the word the user reads elsewhere in the plugin.
 */
internal fun labelRenderer(label: (Any?) -> String?) = object : javax.swing.DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: javax.swing.JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): java.awt.Component =
        super.getListCellRendererComponent(list, label(value) ?: value, index, isSelected, cellHasFocus)
}

internal fun csvSet(s: String): Set<String> =
    s.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

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
