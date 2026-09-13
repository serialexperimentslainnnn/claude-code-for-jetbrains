package dev.lain.claudejb.controller.mcp.tools.ops

import dev.lain.claudejb.controller.mcp.TargetContext
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActionToolSpecsTest {

    private val all = listOf(ActionTools.ACTIONS, ActionTools.MENU, ActionTools.APPEARANCE, ActionTools.UI)

    @Test
    fun `the tool names are pinned, and only the switches act on the IDE`() {
        assertEquals(listOf("actions", "menu", "appearance", "ui"), all.map { it.name })
        assertFalse(ActionTools.ACTIONS.mutates)
        assertFalse(ActionTools.MENU.mutates)
        assertTrue(ActionTools.APPEARANCE.mutates && ActionTools.UI.mutates)
    }

    @Test
    fun `every appearance mode and interface part is a View menu action id, and the parameter lists them`() {
        assertEquals(
            listOf("presentation", "distraction_free", "full_screen", "zen", "compact", "assistant"),
            ActionTools.APPEARANCE_MODES.keys.toList(),
        )
        assertEquals(listOf("toolbar", "navigation_bar", "tool_window_bars", "status_bar", "main_menu"), ActionTools.UI_PARTS.keys.toList())
        (ActionTools.APPEARANCE_MODES.values + ActionTools.UI_PARTS.values).forEach { assertTrue(ACTION_ID.matches(it), it) }
        val mode = ActionTools.APPEARANCE.params.first { it.name == "mode" }
        ActionTools.APPEARANCE_MODES.keys.forEach { assertTrue(it in mode.description, "appearance.mode does not list $it") }
        val part = ActionTools.UI.params.first { it.name == "part" }
        ActionTools.UI_PARTS.keys.forEach { assertTrue(it in part.description, "ui.part does not list $it") }
    }

    @Test
    fun `ide_action takes one target of path, hash or node, and a caret that starts at 1`() {
        assertEquals(listOf("path", "line", "column", "hash", "node"), TargetContext.PARAMS.map { it.name })
        TargetContext.PARAMS.forEach { assertFalse(it.required, it.name) }
        val file = TargetContext.target(ToolArgs(buildJsonObject { put("path", "src/A.kt") }, null))
        assertTrue(file.named && file.line == 1 && file.column == 1)
        assertFalse(TargetContext.target(ToolArgs(buildJsonObject { }, null)).named)
        assertThrows(ToolException::class.java) { TargetContext.target(args("path" to "a", "hash" to "abcd")) }
        assertThrows(ToolException::class.java) { TargetContext.target(args("path" to "a", "line" to "0")) }
    }

    private fun args(vararg pairs: Pair<String, String>) = ToolArgs(buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }, null)

    private companion object {
        val ACTION_ID = Regex("[A-Z][A-Za-z]+")
    }
}
