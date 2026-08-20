package dev.lain.claudejb.ui

import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import dev.lain.claudejb.settings.ClaudeSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable

/**
 * One block of the settings form.
 *
 * [addTo] is the only half that knows about layout; everything else works against
 * [ClaudeSettings.State] directly and never against a bound property. That is deliberate:
 * `ClaudeSettings.reload` swaps the whole state object when another IDE writes the same
 * document, so a binding captured on one instance would keep writing to a state nobody reads.
 */
internal interface SettingsSection {

    fun addTo(panel: Panel)

    fun reset(s: ClaudeSettings.State)

    fun apply(s: ClaudeSettings.State)

    fun changedFields(s: ClaudeSettings.State): List<Boolean>

    @Throws(ConfigurationException::class)
    fun validate() = Unit

    fun dispose() = Unit
}

private const val SCROLL_UNIT = 16
private const val SCROLL_GUTTER = 12

/**
 * The scroll pane every Claude settings page is wrapped in.
 *
 * There is no horizontal scrollbar, and — the part that actually matters — the form is given the
 * viewport's width rather than its own preferred one. Swing sizes a scrolled view to what it asks
 * for unless the view says otherwise, so a form wider than the window used to be clipped off the
 * right edge instead of reflowing. Tracking the width is what lets the DSL's own wrapping work.
 */
internal fun settingsScroller(built: JComponent): JComponent =
    JBScrollPane(WidthTrackingHolder(built)).apply {
        border = JBUI.Borders.empty()
        viewport.isOpaque = false
        isOpaque = false
        verticalScrollBar.unitIncrement = JBUIScale.scale(SCROLL_UNIT)
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
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

/**
 * A set of checkboxes over a fixed vocabulary, stored as one CSV field.
 *
 * Two columns rather than four: a `GridLayout` has a rigid minimum width of columns × widest cell, and
 * four columns of tool names was one of the things pushing the page off its own right edge.
 */
internal class CheckboxGroup(options: List<String>, columns: Int = DEFAULT_COLUMNS) {

    private val boxes = LinkedHashMap<String, JBCheckBox>().apply {
        options.forEach { put(it, JBCheckBox(it)) }
    }

    val component: JComponent = JPanel(GridLayout(0, columns, JBUIScale.scale(GAP_X), JBUIScale.scale(GAP_Y))).apply {
        isOpaque = false
        boxes.values.forEach { add(it) }
    }

    fun text(): String = boxes.filterValues { it.isSelected }.keys.joinToString(",")

    fun setFrom(csv: String) {
        val selected = csvSet(csv)
        boxes.forEach { (name, box) -> box.isSelected = name in selected }
    }

    private companion object {
        const val DEFAULT_COLUMNS = 2
        const val GAP_X = 8
        const val GAP_Y = 2
    }
}

/**
 * The viewport view behind [settingsScroller], last in the file on purpose.
 *
 * It is the only class here with an `init` block, and `InitOrderContractTest` scans a file for the first one
 * and flags every property declared below it. Keeping it at the bottom is what stops that contract firing on
 * a class it was never about.
 */
private class WidthTrackingHolder(view: JComponent) : JPanel(BorderLayout()), Scrollable {

    init {
        isOpaque = false
        border = JBUI.Borders.emptyRight(SCROLL_GUTTER)
        add(view, BorderLayout.NORTH)
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int) =
        JBUIScale.scale(SCROLL_UNIT)

    override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int) = visible.height

    override fun getScrollableTracksViewportWidth() = true

    override fun getScrollableTracksViewportHeight() = false
}
