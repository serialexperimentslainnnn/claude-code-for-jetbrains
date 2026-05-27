package dev.lain.claudejb.diff

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Unit tests for [HunkSelection]'s pure logic — reconstruct (rebuild file text from accepted hunks) and
 * encodeInput (narrow the tool input so the binary writes exactly the selection). computeHunks needs the
 * platform diff engine (Application/EDT) and is exercised manually, not here: what matters for correctness
 * is that, given a set of hunks, the text we send and the input we encode are exactly right.
 */
class HunkSelectionTest {

    // current "a\nb\nc" -> proposed "a\nB\nc": single hunk over line index 1.
    private val currentLines = listOf("a", "b", "c")
    private val proposedLines = listOf("a", "B", "c")
    private val oneHunk = listOf(Hunk(index = 0, start1 = 1, end1 = 2, start2 = 1, end2 = 2, preview = "B"))

    // --- reconstruct ---

    @Test
    fun `reconstruct with no accepted hunks equals current text`() {
        assertEquals("a\nb\nc", HunkSelection.reconstruct(currentLines, proposedLines, oneHunk, emptySet()))
    }

    @Test
    fun `reconstruct with all hunks accepted equals proposed text`() {
        assertEquals("a\nB\nc", HunkSelection.reconstruct(currentLines, proposedLines, oneHunk, setOf(0)))
    }

    @Test
    fun `reconstruct mixes accepted and rejected hunks`() {
        // current "a\nb\nc\nd\ne" -> proposed "a\nB\nc\nD\ne": two independent hunks (lines 1 and 3).
        val cur = listOf("a", "b", "c", "d", "e")
        val prop = listOf("a", "B", "c", "D", "e")
        val hunks = listOf(
            Hunk(index = 0, start1 = 1, end1 = 2, start2 = 1, end2 = 2, preview = "B"),
            Hunk(index = 1, start1 = 3, end1 = 4, start2 = 3, end2 = 4, preview = "D"),
        )
        // accept only the second hunk -> b stays, d becomes D.
        assertEquals("a\nb\nc\nD\ne", HunkSelection.reconstruct(cur, prop, hunks, setOf(1)))
        // accept only the first hunk -> b becomes B, d stays.
        assertEquals("a\nB\nc\nd\ne", HunkSelection.reconstruct(cur, prop, hunks, setOf(0)))
    }

    // --- encodeInput ---

    @Test
    fun `encodeInput Write overwrites content and keeps file_path`() {
        val original = buildJsonObject {
            put("file_path", "/tmp/a.kt")
            put("content", "FULL PROPOSED")
        }
        val out = HunkSelection.encodeInput("Write", original, currentText = "ignored", selectedText = "PARTIAL")
        assertEquals("PARTIAL", out["content"]?.jsonPrimitive?.content)
        assertEquals("/tmp/a.kt", out["file_path"]?.jsonPrimitive?.content)
    }

    @Test
    fun `encodeInput Edit collapses to old new replace_all false`() {
        val original = buildJsonObject {
            put("file_path", "/tmp/a.kt")
            put("old_string", "x")
            put("new_string", "y")
            put("replace_all", true)
        }
        val out = HunkSelection.encodeInput("Edit", original, currentText = "CUR", selectedText = "SEL")
        assertEquals("/tmp/a.kt", out["file_path"]?.jsonPrimitive?.content)
        assertEquals("CUR", out["old_string"]?.jsonPrimitive?.content)
        assertEquals("SEL", out["new_string"]?.jsonPrimitive?.content)
        assertFalse(out["replace_all"]?.jsonPrimitive?.content?.toBoolean() ?: true)
    }

    @Test
    fun `encodeInput MultiEdit collapses to a single edit`() {
        val original = buildJsonObject {
            put("file_path", "/tmp/a.kt")
        }
        val out = HunkSelection.encodeInput("MultiEdit", original, currentText = "CUR", selectedText = "SEL")
        assertEquals("/tmp/a.kt", out["file_path"]?.jsonPrimitive?.content)
        val edits = out["edits"] as JsonArray
        assertEquals(1, edits.size)
        val edit = edits[0].jsonObject
        assertEquals("CUR", edit["old_string"]?.jsonPrimitive?.content)
        assertEquals("SEL", edit["new_string"]?.jsonPrimitive?.content)
    }

    @Test
    fun `encodeInput unknown tool returns the original input unchanged`() {
        val original = buildJsonObject { put("content", "x") }
        val out = HunkSelection.encodeInput("Read", original, currentText = "CUR", selectedText = "SEL")
        assertSame(original, out)
    }
}
