package dev.lain.claudejb.controller.mcp.tools.run

import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DebugToolSpecsTest {

    private val specs: List<ToolSpec> =
        listOf(DebugSpecs.SESSION, DebugSpecs.STEP, DebugSpecs.FRAMES, DebugSpecs.VALUES, BreakpointTools.BREAKPOINT)

    @Test
    fun `the two domains expose five tools by their planned names`() {
        assertEquals(listOf("session", "step", "frames", "values", "breakpoint"), specs.map { it.name })
    }

    @Test
    fun `an expression that runs inside the debuggee travels as code, so the guard scans it as a command`() {
        val names = DebugSpecs.VALUES.params.map { it.name }
        assertTrue("code" in names) { names.toString() }
        assertTrue(names.none { it in setOf("expression", "expr") }) { names.toString() }
    }

    @Test
    fun `a file argument is always named path, a breakpoint's included`() {
        for (spec in listOf(DebugSpecs.STEP, BreakpointTools.BREAKPOINT)) {
            val names = spec.params.map { it.name }
            assertTrue("path" in names && "line" in names) { "${spec.name}: $names" }
            assertTrue(names.none { it in setOf("file", "filename", "file_path") }) { "${spec.name}: $names" }
        }
    }

    @Test
    fun `no argument borrows a guard command key for something that is not a command`() {
        val commandKeys = setOf("command", "cmd", "script", "shell", "exec", "run", "args", "argv", "arguments", "stdin", "cmdline", "entrypoint")
        val borrowed = specs.flatMap { spec -> spec.params.map { it.name }.filter { it in commandKeys }.map { "${spec.name}.$it" } }
        assertEquals(emptyList<String>(), borrowed)
    }

    @Test
    fun `every action and kind the code accepts is enumerated in the description or the parameter`() {
        assertEnumerated(DebugSpecs.SESSION, "action", listOf("start", "stop", "status", "list"))
        assertEnumerated(DebugSpecs.STEP, "kind", listOf("over", "into", "out", "resume", "pause", "run_to", "wait"))
        assertEnumerated(DebugSpecs.VALUES, "action", listOf("list", "eval", "set"))
        assertEnumerated(BreakpointTools.BREAKPOINT, "action", listOf("add", "remove", "list"))
    }

    @Test
    fun `tools that move the debuggee or the gutter are marked as mutating, reading the stack is not`() {
        assertEquals(
            listOf(true, true, false, true, true),
            specs.map { it.mutates },
        ) { specs.map { "${it.name}=${it.mutates}" }.toString() }
    }

    @Test
    fun `every wait stays inside the endpoint's timeout and is bounded on both ends`() {
        assertTrue(DebugSpecs.MAX_WAIT * 2 * MILLIS < ToolSpec.DEFAULT_TIMEOUT_MILLIS)
        assertEquals(DebugSpecs.DEFAULT_STEP_WAIT, DebugSpecs.waitSeconds(args("{}"), DebugSpecs.DEFAULT_STEP_WAIT))
        assertEquals(7, DebugSpecs.waitSeconds(args("""{"wait":"7"}"""), 1))
        assertThrows<ToolException> { DebugSpecs.waitSeconds(args("""{"wait":"0"}"""), 1) }
        assertThrows<ToolException> { DebugSpecs.waitSeconds(args("""{"wait":"${DebugSpecs.MAX_WAIT + 1}"}"""), 1) }
    }

    @Test
    fun `every schema is strict and names only the selector as required`() {
        for (spec in specs) {
            val required = spec.params.filter { it.required }.map { it.name }
            val expected = when (spec.name) {
                "frames" -> emptyList()
                "step" -> listOf("kind")
                else -> listOf("action")
            }
            assertEquals(expected, required) { spec.name }
            assertEquals("false", spec.inputSchema["additionalProperties"].toString()) { spec.name }
        }
    }

    private fun assertEnumerated(spec: ToolSpec, selector: String, values: List<String>) {
        val text = spec.params.single { it.name == selector }.description + " " + spec.description
        val missing = values.filterNot { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(text) }
        assertEquals(emptyList<String>(), missing) { "${spec.name}: $text" }
    }

    private fun args(json: String): ToolArgs = ToolArgs(Json.parseToJsonElement(json).jsonObject)

    private companion object {
        const val MILLIS = 1000L
    }
}
