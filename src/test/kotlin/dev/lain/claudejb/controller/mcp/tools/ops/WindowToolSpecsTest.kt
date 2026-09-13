package dev.lain.claudejb.controller.mcp.tools.ops

import dev.lain.claudejb.controller.mcp.tools.code.RecentTools
import dev.lain.claudejb.controller.mcp.tools.code.Schemes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WindowToolSpecsTest {

    private val window = listOf(WindowTools.TABS, WindowTools.LAYOUT, WindowTools.ZOOM, WindowTools.EDITOR_SETTINGS)
    private val recent = listOf(RecentTools.RECENT, RecentTools.NAVIGATE_HISTORY, RecentTools.COMPARE_CLIPBOARD, RecentTools.SCHEME)

    @Test
    fun `the tool names are pinned per domain, and only the readers are read-only`() {
        assertEquals(listOf("tabs", "layout", "zoom", "editor_settings"), window.map { it.name })
        assertEquals(listOf("recent", "navigate_history", "compare_clipboard", "scheme"), recent.map { it.name })
        window.forEach { assertTrue(it.mutates, it.name) }
        assertFalse(RecentTools.RECENT.mutates)
        assertFalse(RecentTools.COMPARE_CLIPBOARD.mutates)
        assertTrue(RecentTools.NAVIGATE_HISTORY.mutates && RecentTools.SCHEME.mutates)
    }

    @Test
    fun `every table entry is a platform action id, and every parameter lists its table`() {
        (WindowTools.TAB_ACTIONS.values + WindowTools.LAYOUT_ACTIONS.values + WindowTools.EDITOR_ZOOM.values + WindowTools.IDE_ZOOM.values)
            .forEach { assertTrue(ACTION_ID.matches(it), it) }
        val tabs = WindowTools.TABS.params.first { it.name == "action" }
        WindowTools.TAB_ACTIONS.keys.forEach { assertTrue(it in tabs.description, "tabs.action does not list $it") }
        val layout = WindowTools.LAYOUT.params.first { it.name == "action" }
        WindowTools.LAYOUT_ACTIONS.keys.forEach { assertTrue(it in layout.description, "layout.action does not list $it") }
        assertEquals(WindowTools.EDITOR_ZOOM.keys, WindowTools.IDE_ZOOM.keys)
        val setting = WindowTools.EDITOR_SETTINGS.params.first { it.name == "setting" }
        WindowTools.EDITOR_SETTINGS_KEYS.forEach { assertTrue(it in setting.description, "editor_settings.setting does not list $it") }
        val kind = RecentTools.SCHEME.params.first { it.name == "kind" }
        Schemes.KINDS.forEach { assertTrue(it in kind.description, "scheme.kind does not list $it") }
        val direction = RecentTools.NAVIGATE_HISTORY.params.first { it.name == "direction" }
        RecentTools.DIRECTIONS.forEach { assertTrue(it in direction.description, "navigate_history.direction does not list $it") }
    }

    private companion object {
        val ACTION_ID = Regex("[A-Z][A-Za-z]+")
    }
}
