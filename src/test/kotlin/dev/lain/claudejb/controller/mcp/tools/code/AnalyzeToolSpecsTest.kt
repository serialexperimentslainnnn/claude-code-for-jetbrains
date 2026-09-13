package dev.lain.claudejb.controller.mcp.tools.code

import dev.lain.claudejb.model.mcp.Batch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnalyzeToolSpecsTest {

    private val analyze = listOf(AnalyzeTools.INSPECT_SCOPE, AnalyzeTools.CLEANUP, AnalyzeTools.FILE_DEPENDENCIES, AnalyzeTools.DATAFLOW)
    private val analysis = listOf(AnalysisTools.STACK_TRACE, AnalysisTools.DUPLICATES, AnalysisTools.INFER_NULLITY, AnalysisTools.RELATED)
    private val views = listOf(ViewTools.DIFF_SHOW, ViewTools.COMPARE, ViewTools.MARK_AS, ViewTools.OPEN_IN)
    private val files = listOf(FileTools.COPY_PATH, FileTools.FILE_TYPE, FileTools.IGNORE, FileTools.DELETE_FILE)
    private val refactor = listOf(RefactorOpsTools.INTRODUCE, RefactorOpsTools.EXTRACT, RefactorOpsTools.INLINE, RefactorOpsTools.MEMBERS)

    @Test
    fun `the tool names are pinned per domain`() {
        assertEquals(listOf("inspect_scope", "cleanup", "file_dependencies", "dataflow"), analyze.map { it.name })
        assertEquals(listOf("stack_trace", "duplicates", "infer_nullity", "related"), analysis.map { it.name })
        assertEquals(listOf("diff_show", "compare", "mark_as", "open_in"), views.map { it.name })
        assertEquals(listOf("copy_path", "file_type", "ignore", "delete_file"), files.map { it.name })
        assertEquals(listOf("introduce", "extract", "inline", "members"), refactor.map { it.name })
    }

    @Test
    fun `what changes the project says so, and what only reads or shows does not`() {
        (refactor + listOf(AnalyzeTools.INSPECT_SCOPE, AnalyzeTools.CLEANUP, ViewTools.MARK_AS, FileTools.FILE_TYPE, FileTools.IGNORE, FileTools.DELETE_FILE))
            .forEach { assertTrue(it.mutates, it.name) }
        listOf(AnalyzeTools.FILE_DEPENDENCIES, AnalyzeTools.DATAFLOW, AnalysisTools.STACK_TRACE, AnalysisTools.RELATED, ViewTools.DIFF_SHOW, ViewTools.COMPARE, FileTools.COPY_PATH)
            .forEach { assertFalse(it.mutates, it.name) }
    }

    @Test
    fun `every refactoring table entry is a platform action id and its parameter lists the names`() {
        val tables = listOf(
            RefactorOpsTools.INTRODUCE_ACTIONS to RefactorOpsTools.INTRODUCE,
            RefactorOpsTools.EXTRACT_ACTIONS to RefactorOpsTools.EXTRACT,
            RefactorOpsTools.MEMBER_ACTIONS to RefactorOpsTools.MEMBERS,
            AnalysisTools.RELATED_ACTIONS to AnalysisTools.RELATED,
        )
        tables.forEach { (table, spec) ->
            table.values.forEach { assertTrue(ACTION_ID.matches(it), it) }
            table.keys.forEach { assertTrue(it in spec.description, "${spec.name} does not list $it") }
        }
        val kind = ViewTools.MARK_AS.params.first { it.name == "kind" }
        ViewTools.ROOT_KINDS.forEach { assertTrue(it in kind.description, "mark_as.kind does not list $it") }
        FileTools.IGNORE_FILES.forEach { assertTrue(it in FileTools.IGNORE.description, "ignore does not list $it") }
    }

    @Test
    fun `a selection is line to to_line, and delete_file takes its paths in one call`() {
        listOf(RefactorOpsTools.INTRODUCE, RefactorOpsTools.EXTRACT, RefactorOpsTools.INLINE, RefactorOpsTools.MEMBERS).forEach { spec ->
            assertTrue(spec.params.any { it.name == "to_line" } && spec.params.any { it.name == "to_column" }, spec.name)
        }
        assertTrue("delete_file" in Batch.ONE_CALL_LISTS)
        assertEquals("paths", FileTools.DELETE_FILE.params.single().name)
    }

    private companion object {
        val ACTION_ID = Regex("[A-Z][A-Za-z.]+")
    }
}
