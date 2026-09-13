package dev.lain.claudejb.controller.mcp

import dev.lain.claudejb.controller.mcp.tools.code.EditTools
import dev.lain.claudejb.controller.mcp.tools.code.EditorTools
import dev.lain.claudejb.controller.mcp.tools.code.FormatTools
import dev.lain.claudejb.controller.mcp.tools.code.HierarchyTools
import dev.lain.claudejb.controller.mcp.tools.code.RefactorTools
import dev.lain.claudejb.model.mcp.ToolSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodeToolSpecsContractTest {

    private val writing: Map<String, List<ToolSpec>> = mapOf(
        "edit" to listOf(EditTools.REPLACE_TEXT, EditTools.INSERT_TEXT, EditTools.CREATE_FILE),
        "refactor" to listOf(RefactorTools.RENAME, RefactorTools.MOVE_FILE, RefactorTools.SAFE_DELETE),
        "format" to listOf(FormatTools.REFORMAT, FormatTools.OPTIMIZE_IMPORTS),
    )

    private val reading: Map<String, List<ToolSpec>> = mapOf(
        "editor" to listOf(EditorTools.OPEN_FILE, EditorTools.ACTIVE_FILE, EditorTools.INDEX_STATUS),
        "hierarchy" to listOf(HierarchyTools.HIERARCHY),
    )

    private val specs: List<ToolSpec> = (writing.values + reading.values).flatten()

    @Test
    fun `every domain keeps its tool names, so renaming one is a deliberate change`() {
        val names = (writing + reading).mapValues { (_, specs) -> specs.map { it.name } }
        assertEquals(
            mapOf(
                "edit" to listOf("replace_text", "insert_text", "create_file"),
                "refactor" to listOf("rename", "move_file", "safe_delete"),
                "format" to listOf("reformat", "optimize_imports"),
                "editor" to listOf("open_file", "active_file", "index_status"),
                "hierarchy" to listOf("hierarchy"),
            ),
            names,
        )
    }

    @Test
    fun `tools that write say so, and tools that only look do not`() {
        val silentWriters = writing.values.flatten().filterNot { it.mutates }.map { it.name }
        val loudReaders = reading.values.flatten().filter { it.mutates }.map { it.name }
        assertEquals(emptyList<String>(), silentWriters) { "the host approves and orders mutating tools by this flag" }
        assertEquals(emptyList<String>(), loudReaders) { "a read-only tool flagged as mutating asks for approval it does not need" }
    }

    @Test
    fun `no parameter is named like a shell command, since the guard would judge its value as one`() {
        val offenders = specs.flatMap { spec -> spec.params.filter { COMMAND_KEY.matches(it.name) }.map { "${spec.name}.${it.name}" } }
        assertEquals(emptyList<String>(), offenders) {
            "The guard treats these keys as commands and runs the command rules over their values; none of these tools takes one."
        }
    }

    @Test
    fun `edit bodies travel only in content keys, which is what makes the guard scan them for injection`() {
        val bodies = specs.flatMap { spec -> spec.params.filter { CONTENT_KEY.matches(it.name) }.map { "${spec.name}.${it.name}" } }
        assertEquals(
            listOf("replace_text.old_string", "replace_text.new_string", "insert_text.content", "create_file.content"),
            bodies,
        )
        val bodyLike = specs.flatMap { spec ->
            spec.params.filter { it.name in setOf("text", "body", "source", "value", "replacement") }.map { "${spec.name}.${it.name}" }
        }
        assertEquals(emptyList<String>(), bodyLike) { "a body under another key would slip past the content scan" }
    }

    @Test
    fun `every parameter has a type the schema accepts and a description`() {
        for (spec in specs) {
            for (param in spec.params) {
                assertTrue(param.type in setOf("string", "integer", "boolean", "array")) { "${spec.name}.${param.name}: ${param.type}" }
                assertTrue((param.type == "array") == (param.items != null)) { "${spec.name}.${param.name}: an array says what it holds" }
                assertTrue(param.description.isNotBlank()) { "${spec.name}.${param.name} has no description" }
            }
            assertTrue(spec.description.length in DESCRIPTION_RANGE) { "${spec.name}: ${spec.description.length} chars" }
        }
    }

    private companion object {

        val COMMAND_KEY = Regex(
            """^(cmd|command|commands|script|shell|shell_?command|exec|execute|run|args|argv|arguments""" +
                """|code|program|pty_?input|stdin|cmdline|entrypoint)$""",
            RegexOption.IGNORE_CASE,
        )

        val CONTENT_KEY = Regex(
            """^(old_?string|new_?string|old_?str|new_?str|content|contents|old_?source|new_?source)$""",
            RegexOption.IGNORE_CASE,
        )

        val DESCRIPTION_RANGE = 40..260
    }
}
