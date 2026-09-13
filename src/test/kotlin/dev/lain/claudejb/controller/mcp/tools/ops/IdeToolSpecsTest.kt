package dev.lain.claudejb.controller.mcp.tools.ops

import dev.lain.claudejb.model.mcp.ToolSpec
import dev.lain.claudejb.model.permission.scan.ToolInputScanner
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdeToolSpecsTest {

    private val ide = listOf(IdeTools.IDE_ACTION, IdeTools.TOOL_WINDOW, IdeTools.SETTINGS_OPEN, IdeTools.PLUGINS)
    private val all = ide + NotifyTools.NOTIFY

    @Test
    fun `the tool names are pinned per domain`() {
        assertEquals(listOf("ide_action", "tool_window", "settings_open", "plugins"), ide.map { it.name })
        assertEquals("notify", NotifyTools.NOTIFY.name)
        assertEquals(all.size, all.map { it.name }.toSet().size, "two ops tools share a name")
    }

    @Test
    fun `what touches the IDE says so, and the plugin list does not`() {
        (all - IdeTools.PLUGINS).forEach { assertTrue(it.mutates, "${it.name} acts on the IDE and must say so") }
        assertFalse(IdeTools.PLUGINS.mutates, "plugins is read-only")
    }

    @Test
    fun `no parameter is spelled like a shell command, so the guard never tokenises an action id as one`() {
        all.flatMap { spec -> spec.params.map { spec.name to it.name } }.forEach { (tool, param) ->
            assertNull(ToolInputScanner.commandText(buildJsonObject { put(param, "x") }), "$tool.$param reads as a command key")
        }
    }

    @Test
    fun `the tool window actions and the notification kinds are enumerated where the model reads them`() {
        assertEquals(listOf("open", "close", "list"), IdeTools.TOOL_WINDOW_ACTIONS)
        val action = IdeTools.TOOL_WINDOW.params.first { it.name == "action" }
        IdeTools.TOOL_WINDOW_ACTIONS.forEach { assertTrue(it in action.description, "tool_window.action does not list $it") }
        assertEquals(listOf("info", "warning", "error"), NotifyTools.KINDS.keys.toList())
        val kind = NotifyTools.NOTIFY.params.first { it.name == "kind" }
        NotifyTools.KINDS.keys.forEach { assertTrue(it in kind.description, "notify.kind does not list $it") }
        assertFalse(kind.required)
    }

    @Test
    fun `an action id is the one required argument of ide_action`() {
        assertEquals(listOf("action_id"), IdeTools.IDE_ACTION.params.filter { it.required }.map { it.name })
    }

    @Test
    fun `a notification needs a title and a message, and says markup is not rendered`() {
        assertEquals(listOf("title", "message"), NotifyTools.NOTIFY.params.filter { it.required }.map { it.name })
        assertTrue("never rendered" in NotifyTools.NOTIFY.description)
    }

    @Test
    fun `every list has a max that names its default, and no tool waits longer than the default`() {
        listOf(IdeTools.TOOL_WINDOW, IdeTools.PLUGINS).forEach { spec ->
            val max = spec.params.first { it.name == "max" }
            assertEquals("integer", max.type, spec.name)
            assertTrue("default" in max.description, "${spec.name}.max names no default")
        }
        all.forEach { assertEquals(ToolSpec.DEFAULT_TIMEOUT_MILLIS, it.timeoutMillis, it.name) }
        assertTrue(all.flatMap { it.params }.all { it.type in setOf("string", "integer", "boolean") })
    }
}
