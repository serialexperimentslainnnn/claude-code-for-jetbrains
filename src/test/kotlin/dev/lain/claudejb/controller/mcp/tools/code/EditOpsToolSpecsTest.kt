package dev.lain.claudejb.controller.mcp.tools.code

import dev.lain.claudejb.model.mcp.Batch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EditOpsToolSpecsTest {

    private val editOps = listOf(EditOpsTools.UNDO, EditOpsTools.REDO, EditOpsTools.SEARCH_REPLACE, EditOpsTools.LINE_OPS)

    @Test
    fun `the tool names are pinned and every one of them changes the project`() {
        assertEquals(listOf("undo", "redo", "search_replace", "line_ops"), editOps.map { it.name })
        editOps.forEach { assertTrue(it.mutates, it.name) }
        assertTrue(EditorTools.EDITOR_ACTION.mutates)
        assertEquals("editor_action", EditorTools.EDITOR_ACTION.name)
    }

    @Test
    fun `search_replace takes its paths in one call, so one card covers the whole replacement`() {
        assertTrue("search_replace" in Batch.ONE_CALL_LISTS)
        assertEquals(listOf("query", "replacement"), EditOpsTools.SEARCH_REPLACE.params.filter { it.required }.map { it.name })
        assertTrue(EditOpsTools.SEARCH_REPLACE.params.any { it.name == "paths" && it.type == "array" })
    }

    @Test
    fun `every editor and line action maps to a platform action id and is listed in the parameter`() {
        (EditorTools.EDITOR_ACTIONS.values + EditOpsTools.LINE_ACTIONS.values).forEach { assertTrue(ACTION_ID.matches(it), it) }
        val action = EditorTools.EDITOR_ACTION.params.first { it.name == "action" }
        assertTrue(action.required)
        assertEquals("path", EditorTools.EDITOR_ACTION.params.first { it.required && it.name != "action" }.name)
        val line = EditOpsTools.LINE_OPS.params.first { it.name == "action" }
        EditOpsTools.LINE_ACTIONS.keys.forEach { assertTrue(it in line.description, "line_ops.action does not list $it") }
        listOf("override", "comment_line", "move_line_up", "fold_all", "quick_doc").forEach {
            assertTrue(it in EditorTools.EDITOR_ACTIONS && it in EditorTools.EDITOR_ACTION.description, it)
        }
    }

    private companion object {
        val ACTION_ID = Regex("[A-Z][A-Za-z]+")
    }
}
