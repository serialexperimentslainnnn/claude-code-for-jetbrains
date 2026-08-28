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

internal fun settingsScroller(built: JComponent): JComponent =
    JBScrollPane(WidthTrackingHolder(built)).apply {
        border = JBUI.Borders.empty()
        viewport.isOpaque = false
        isOpaque = false
        verticalScrollBar.unitIncrement = JBUIScale.scale(SCROLL_UNIT)
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }

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
