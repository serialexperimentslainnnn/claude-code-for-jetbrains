package dev.lain.claudejb.diff

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DiffPresenter]'s pure reconstruction logic — proposedContent (and the private applyEdit it
 * drives) plus filePathOf. The diff-opening methods need a Project/EDT and are exercised manually, not here:
 * what matters for correctness is that the right-hand "Proposed by Claude" content we preview matches exactly
 * what the binary will write when we answer `allow`.
 */
class DiffPresenterTest {

    // --- Write ---

    @Test
    fun `Write returns content verbatim`() {
        val input = buildJsonObject {
            put("file_path", "a.kt")
            put("content", "hello world")
        }
        assertEquals("hello world", DiffPresenter.proposedContent("Write", input, "ignored"))
    }

    @Test
    fun `Write without content yields empty string`() {
        val input = buildJsonObject { put("file_path", "a.kt") }
        assertEquals("", DiffPresenter.proposedContent("Write", input, "ignored"))
    }

    // --- Edit ---

    @Test
    fun `Edit replaces first occurrence by default`() {
        val input = buildJsonObject {
            put("old_string", "foo")
            put("new_string", "bar")
        }
        assertEquals("bar foo", DiffPresenter.proposedContent("Edit", input, "foo foo"))
    }

    @Test
    fun `Edit with replace_all replaces every occurrence`() {
        val input = buildJsonObject {
            put("old_string", "foo")
            put("new_string", "bar")
            put("replace_all", true)
        }
        assertEquals("bar bar", DiffPresenter.proposedContent("Edit", input, "foo foo"))
    }

    @Test
    fun `Edit without old_string returns null`() {
        val input = buildJsonObject { put("new_string", "bar") }
        assertNull(DiffPresenter.proposedContent("Edit", input, "foo"))
    }

    @Test
    fun `Edit without new_string treats it as empty`() {
        val input = buildJsonObject { put("old_string", "foo") }
        assertEquals(" bar", DiffPresenter.proposedContent("Edit", input, "foo bar"))
    }

    // --- MultiEdit ---

    @Test
    fun `MultiEdit applies edits in chain`() {
        val input = buildJsonObject {
            putJsonArray("edits") {
                addJsonObject {
                    put("old_string", "a")
                    put("new_string", "b")
                }
                addJsonObject {
                    put("old_string", "b")
                    put("new_string", "c")
                }
            }
        }
        // "a" -> "b" -> "c"
        assertEquals("c", DiffPresenter.proposedContent("MultiEdit", input, "a"))
    }

    @Test
    fun `MultiEdit keeps accumulator when an edit's old_string is not found`() {
        val input = buildJsonObject {
            putJsonArray("edits") {
                addJsonObject {
                    put("old_string", "a")
                    put("new_string", "b")
                }
                addJsonObject {
                    // not present in "b" — replaceFirst no-ops, acc kept
                    put("old_string", "zzz")
                    put("new_string", "x")
                }
            }
        }
        assertEquals("b", DiffPresenter.proposedContent("MultiEdit", input, "a"))
    }

    @Test
    fun `MultiEdit without edits array returns null`() {
        val input = buildJsonObject { put("file_path", "a.kt") }
        assertNull(DiffPresenter.proposedContent("MultiEdit", input, "a"))
    }

    // --- unknown tool ---

    @Test
    fun `unknown tool returns null`() {
        val input = buildJsonObject { put("content", "x") }
        assertNull(DiffPresenter.proposedContent("Read", input, "current"))
    }

    // --- filePathOf ---

    @Test
    fun `filePathOf returns file_path when present`() {
        val input = buildJsonObject { put("file_path", "/tmp/a.kt") }
        assertEquals("/tmp/a.kt", DiffPresenter.filePathOf(input))
    }

    @Test
    fun `filePathOf returns null when absent`() {
        val input: JsonObject = buildJsonObject { put("content", "x") }
        assertNull(DiffPresenter.filePathOf(input))
    }
}
