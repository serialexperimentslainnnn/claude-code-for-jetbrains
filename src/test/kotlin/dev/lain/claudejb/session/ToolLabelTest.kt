package dev.lain.claudejb.session

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-JVM coverage of the tool-card label + its jump-to-code path ([ClaudeSession.formatToolUse] /
 * [ClaudeSession.toolFilePath] / [ClaudeSession.relativizeToRoot]).
 *
 * These three decide what the transcript row says AND which substring the frontend turns into a link — the
 * frontend links the path by locating it *inside* the label, so "the label contains exactly the string
 * `toolFilePath` returned" is the contract that must not drift.
 */
class ToolLabelTest {

    private val root = "/home/u/proj"

    private fun input(vararg pairs: Pair<String, String>) = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    // ── relativizeToRoot ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `relativizeToRoot strips the project root`() {
        assertEquals("src/Foo.kt", ClaudeSession.relativizeToRoot("$root/src/Foo.kt", root))
    }

    @Test
    fun `relativizeToRoot tolerates a trailing separator on the root`() {
        assertEquals("src/Foo.kt", ClaudeSession.relativizeToRoot("$root/src/Foo.kt", "$root/"))
    }

    @Test
    fun `relativizeToRoot leaves a path outside the root untouched`() {
        assertEquals("/etc/passwd", ClaudeSession.relativizeToRoot("/etc/passwd", root))
        // A sibling directory that merely shares the root's prefix is NOT inside it.
        assertEquals("/home/u/proj-other/x.kt", ClaudeSession.relativizeToRoot("/home/u/proj-other/x.kt", root))
    }

    @Test
    fun `relativizeToRoot without a root is the identity`() {
        assertEquals("/a/b.kt", ClaudeSession.relativizeToRoot("/a/b.kt", null))
        assertEquals("/a/b.kt", ClaudeSession.relativizeToRoot("/a/b.kt", ""))
    }

    @Test
    fun `relativizeToRoot normalises Windows separators`() {
        assertEquals("src/Foo.kt", ClaudeSession.relativizeToRoot("C:\\p\\src\\Foo.kt", "C:\\p"))
    }

    // ── toolFilePath ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `toolFilePath returns the project-relative path for a file tool`() {
        val i = input("file_path" to "$root/src/Foo.kt")
        assertEquals("src/Foo.kt", ClaudeSession.toolFilePath("Read", i, root))
        assertEquals("src/Foo.kt", ClaudeSession.toolFilePath("Edit", i, root))
        assertEquals("src/Foo.kt", ClaudeSession.toolFilePath("Write", i, root))
        assertEquals("src/Foo.kt", ClaudeSession.toolFilePath("MultiEdit", i, root))
        assertEquals("src/Foo.kt", ClaudeSession.toolFilePath("NotebookEdit", i, root))
    }

    @Test
    fun `toolFilePath is null for a non-file tool even when it carries a file_path`() {
        assertNull(ClaudeSession.toolFilePath("Bash", input("file_path" to "$root/src/Foo.kt"), root))
        assertNull(ClaudeSession.toolFilePath("Grep", input("pattern" to "foo"), root))
    }

    @Test
    fun `toolFilePath is null when the file argument is missing or blank`() {
        assertNull(ClaudeSession.toolFilePath("Read", buildJsonObject { }, root))
        assertNull(ClaudeSession.toolFilePath("Read", input("file_path" to "  "), root))
    }

    // ── formatToolUse ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `formatToolUse shows a file tool's path relative to the project`() {
        val label = ClaudeSession.formatToolUse("Read", input("file_path" to "$root/src/main/Foo.kt"), root)
        assertEquals("Read(src/main/Foo.kt)", label)
    }

    /** The frontend links the path by finding it inside the label — so the label MUST contain it verbatim. */
    @Test
    fun `formatToolUse label contains the toolFilePath verbatim`() {
        val i = input("file_path" to "$root/src/main/Foo.kt")
        val path = ClaudeSession.toolFilePath("Edit", i, root)!!
        assert(ClaudeSession.formatToolUse("Edit", i, root).contains(path)) { "label must embed the linkable path" }
    }

    @Test
    fun `formatToolUse keeps an absolute path when it is outside the project`() {
        val label = ClaudeSession.formatToolUse("Read", input("file_path" to "/etc/hosts"), root)
        assertEquals("Read(/etc/hosts)", label)
    }

    @Test
    fun `formatToolUse without a project root falls back to the raw path`() {
        val label = ClaudeSession.formatToolUse("Read", input("file_path" to "/a/b/Foo.kt"))
        assertEquals("Read(/a/b/Foo.kt)", label)
    }

    @Test
    fun `formatToolUse keeps the non-file tool arguments unchanged`() {
        assertEquals("Bash(ls -la)", ClaudeSession.formatToolUse("Bash", input("command" to "ls -la"), root))
        assertEquals("Grep(TODO)", ClaudeSession.formatToolUse("Grep", input("pattern" to "TODO"), root))
        assertEquals("Task(refactor)", ClaudeSession.formatToolUse("Task", input("description" to "refactor"), root))
    }

    @Test
    fun `formatToolUse is just the tool name when there is no argument`() {
        assertEquals("TodoWrite", ClaudeSession.formatToolUse("TodoWrite", buildJsonObject { }, root))
    }

    // ── mayHaveWrittenUnknownFiles: which tools force a project-tree VFS refresh ──────────────────────────

    @Test
    fun `Bash may have written anything`() {
        assertTrue(ClaudeSession.mayHaveWrittenUnknownFiles("Bash"))
    }

    /** These write files we KNOW: the per-path refresh covers them exactly, no tree crawl needed. */
    @Test
    fun `the file tools are refreshed by path, not by tree`() {
        listOf("Read", "Edit", "Write", "MultiEdit", "NotebookEdit").forEach {
            assertFalse(ClaudeSession.mayHaveWrittenUnknownFiles(it), it)
        }
    }

    @Test
    fun `a mutating MCP tool forces a tree refresh`() {
        listOf(
            "mcp__idea__replace_text_in_file",
            "mcp__idea__create_new_file",
            "mcp__idea__apply_patch",
            "mcp__idea__reformat_file",
            "mcp__idea__rename_refactoring",
            "mcp__fs__delete_file",
            "mcp__fs__move_path",
        ).forEach { assertTrue(ClaudeSession.mayHaveWrittenUnknownFiles(it), it) }
    }

    @Test
    fun `a read-only tool does not force a tree refresh`() {
        listOf("Grep", "Glob", "WebSearch", "WebFetch", "TodoWrite", "mcp__idea__get_file_text_by_path")
            .forEach { assertFalse(ClaudeSession.mayHaveWrittenUnknownFiles(it), it) }
    }

    @Test
    fun `an unknown or blank tool name never triggers a refresh`() {
        assertFalse(ClaudeSession.mayHaveWrittenUnknownFiles(null))
        assertFalse(ClaudeSession.mayHaveWrittenUnknownFiles(""))
        assertFalse(ClaudeSession.mayHaveWrittenUnknownFiles("   "))
    }
}
