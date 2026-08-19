package dev.lain.claudejb.diff

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiffPresenterTest {

    @Test
    fun `diff title does not end in the reviewed file's extension`() {
        for (name in listOf("build.gradle.kts", "App.kt", "main.py", "pom.xml", "script.sh", "a.gradle")) {
            val title = DiffPresenter.diffTitle(name)
            val ext = name.substringAfterLast('.', "")
            assertFalse(
                title.endsWith(".$ext"),
                "Diff title '$title' ends in .$ext — the IDE will treat the diff tab as a file of that type",
            )
        }
    }

    @Test
    fun `diff title still names the file, so tabs stay identifiable`() {
        assertTrue(DiffPresenter.diffTitle("build.gradle.kts").startsWith("build.gradle.kts"))
        assertTrue(DiffPresenter.diffTitle("App.kt").contains("Claude"))
    }

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

    @Test
    fun `MultiEdit skips a malformed edit and keeps applying the rest`() {
        val input = buildJsonObject {
            putJsonArray("edits") {
                addJsonObject {
                    put("old_string", "a")
                    put("new_string", "b")
                }
                addJsonObject {
                    put("new_string", "IGNORED")
                }
                addJsonObject {
                    put("old_string", "b")
                    put("new_string", "c")
                }
            }
        }
        assertEquals("c", DiffPresenter.proposedContent("MultiEdit", input, "a"))
    }

    @Test
    fun `replace_all that is not a boolean defaults to replacing only the first occurrence`() {
        val notPrimitive = buildJsonObject {
            put("old_string", "foo")
            put("new_string", "bar")
            putJsonObject("replace_all") { put("nested", true) }
        }
        assertEquals("bar foo", DiffPresenter.proposedContent("Edit", notPrimitive, "foo foo"))

        val notBoolean = buildJsonObject {
            put("old_string", "foo")
            put("new_string", "bar")
            put("replace_all", 5)
        }
        assertEquals("bar foo", DiffPresenter.proposedContent("Edit", notBoolean, "foo foo"))
    }

    @Test
    fun `unknown tool returns null`() {
        val input = buildJsonObject { put("content", "x") }
        assertNull(DiffPresenter.proposedContent("Read", input, "current"))
    }

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
