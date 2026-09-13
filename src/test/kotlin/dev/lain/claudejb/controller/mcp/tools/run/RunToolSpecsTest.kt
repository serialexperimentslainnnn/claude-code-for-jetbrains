package dev.lain.claudejb.controller.mcp.tools.run

import dev.lain.claudejb.model.mcp.ToolSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RunToolSpecsTest {

    private val long = listOf(BuildTools.BUILD, RunTools.RUN_CONFIGURATION, TestTools.RUN_TESTS, TerminalTools.SHELL)

    private val all = long + listOf(RunTools.RUN_CONFIGURATIONS, RunTools.PROCESSES, TestTools.TESTS)

    @Test
    fun `the shell command is the one argument the guard reads as a command`() {
        assertEquals(listOf("command"), TerminalTools.SHELL.params.map { it.name }.filter { it in COMMAND_KEYS })
        assertTrue(all.filter { it !== TerminalTools.SHELL }.none { spec -> spec.params.any { it.name in COMMAND_KEYS } })
    }

    @Test
    fun `files travel as path so the guard judges them as locations`() {
        assertTrue(TestTools.RUN_TESTS.params.any { it.name == "path" })
        assertTrue(TestTools.TESTS.params.single { it.required }.name == "path")
        assertTrue(all.none { spec -> spec.params.any { it.name == "file" || it.name == "filename" } })
    }

    @Test
    fun `every long tool can be waited on and re-attached, and mutates`() {
        for (spec in long) {
            for (name in listOf("wait", "job", "tail")) {
                assertTrue(spec.params.any { it.name == name && !it.required }) { "${spec.name} lacks optional $name" }
            }
            assertTrue(spec.mutates) { spec.name }
            assertTrue(spec.timeoutMillis > Jobs.MAX_WAIT_SECONDS * MILLIS) { spec.name }
        }
    }

    @Test
    fun `tool names are unique and every parameter has a type the schema accepts`() {
        assertEquals(all.size, all.map(ToolSpec::name).toSet().size)
        assertTrue(all.flatMap { it.params }.all { it.type in setOf("string", "integer", "boolean", "array") })
        assertTrue(all.flatMap { it.params }.all { (it.type == "array") == (it.items != null) })
    }

    @Test
    fun `a run of several configurations or files takes the list where the single one goes`() {
        assertEquals(listOf("name", "names"), RunTools.RUN_CONFIGURATION.params.map { it.name }.filter { it.startsWith("name") })
        assertEquals(listOf("path", "paths"), TestTools.RUN_TESTS.params.map { it.name }.filter { it.startsWith("path") })
        assertTrue(RunTools.RUN_CONFIGURATION.params.none { it.required })
        assertTrue(TestTools.RUN_TESTS.params.none { it.required })
    }

    @Test
    fun `the runner's service messages never reach the tail, the PASS and FAIL lines do`() {
        val tail = OutputTail()
        tail.text("##teamcity[testStarted id='x' name='a test']\nPASS a test\n##teamcity[testFinished id='x']\n")
        tail.line("FAIL other — boom")
        assertEquals(2, tail.lines)
        assertEquals("PASS a test\nFAIL other — boom", tail.tail(10))
    }

    private companion object {
        const val MILLIS = 1000L
        val COMMAND_KEYS = setOf(
            "cmd", "command", "commands", "script", "shell", "exec", "execute", "run", "args", "argv", "arguments",
            "code", "program", "stdin", "cmdline", "entrypoint",
        )
    }
}
