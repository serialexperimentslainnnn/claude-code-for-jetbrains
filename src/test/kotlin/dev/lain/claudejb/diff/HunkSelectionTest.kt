package dev.lain.claudejb.diff

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Unit tests for [HunkSelection.encodeInput] — narrowing the tool input so the binary writes exactly the
 * text the user accepted. What matters for correctness is that the input we encode is exactly right: the
 * binary, not the IDE, performs the write from it.
 */
class HunkSelectionTest {

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

    /**
     * `file_path` is copied only when the original carried one — the encoder must never INVENT the key.
     * The binary resolves a pathless Edit against its own context; emitting `"file_path": null`, or an
     * empty string, would turn "the tool input said nothing about the path" into "the tool input named
     * a path", which is a different write. Both branches of the `?.let` are exercised here, and the rest
     * of the narrowing still has to be produced.
     */
    @Test
    fun `encodeInput omits file_path entirely when the original has none`() {
        val editOut = HunkSelection.encodeInput(
            "Edit",
            buildJsonObject { put("old_string", "x") },
            currentText = "CUR",
            selectedText = "SEL",
        )
        assertFalse(editOut.containsKey("file_path"), "must not synthesize a file_path")
        assertEquals("CUR", editOut["old_string"]?.jsonPrimitive?.content)
        assertEquals("SEL", editOut["new_string"]?.jsonPrimitive?.content)

        val multiOut = HunkSelection.encodeInput(
            "MultiEdit",
            buildJsonObject { },
            currentText = "CUR",
            selectedText = "SEL",
        )
        assertFalse(multiOut.containsKey("file_path"), "must not synthesize a file_path")
        assertEquals(1, (multiOut["edits"] as JsonArray).size)
    }
}
