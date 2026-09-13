package dev.lain.claudejb.model.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TextEditTest {

    private val text = "alpha\nbeta\ngamma\nbeta\n"

    @Test
    fun `one match is replaced and reported with its line`() {
        val outcome = TextEdit.replace(text, "gamma", "delta", all = false)
        assertEquals("alpha\nbeta\ndelta\nbeta\n", outcome.text)
        assertEquals(1, outcome.count)
        assertEquals(listOf(3), outcome.lines)
    }

    @Test
    fun `a missing old_string says it is matched literally`() {
        val error = assertThrows<ToolException> { TextEdit.replace(text, "omega", "x", all = false) }
        assertTrue(error.message!!.contains("not found")) { error.message }
        assertTrue(error.message!!.contains("literally")) { error.message }
    }

    @Test
    fun `an ambiguous old_string names every matching line`() {
        val error = assertThrows<ToolException> { TextEdit.replace(text, "beta", "x", all = false) }
        assertTrue(error.message!!.contains("2 times")) { error.message }
        assertTrue(error.message!!.contains("lines 2, 4")) { error.message }
        assertTrue(error.message!!.contains("replace_all")) { error.message }
    }

    @Test
    fun `replace_all changes every occurrence and reports the lines in the new text`() {
        val outcome = TextEdit.replace(text, "beta", "b\nc", all = true)
        assertEquals("alpha\nb\nc\ngamma\nb\nc\n", outcome.text)
        assertEquals(2, outcome.count)
        assertEquals(listOf(2, 5), outcome.lines)
    }

    @Test
    fun `an empty or identical old_string is refused before any search`() {
        assertThrows<ToolException> { TextEdit.replace(text, "", "x", all = true) }
        assertThrows<ToolException> { TextEdit.replace(text, "beta", "beta", all = true) }
    }

    @Test
    fun `carriage returns outside the match survive untouched`() {
        val outcome = TextEdit.replace("a\r\nb\r\n", "b", "c", all = false)
        assertEquals("a\r\nc\r\n", outcome.text)
    }

    @Test
    fun `insert goes before the given line and adds the missing newline`() {
        val outcome = TextEdit.insertAt(text, 1, "zero")
        assertEquals("zero\nalpha\nbeta\ngamma\nbeta\n", outcome.text)
        assertEquals(1, outcome.count)
        assertEquals(listOf(1), outcome.lines)
        assertEquals("alpha\nbeta\nx\ny\ngamma\nbeta\n", TextEdit.insertAt(text, 3, "x\ny\n").text)
        assertEquals(2, TextEdit.insertAt(text, 3, "x\ny\n").count)
    }

    @Test
    fun `one past the last line appends, with a newline first when the file lacks one`() {
        assertEquals("alpha\nbeta\ngamma\nbeta\nend\n", TextEdit.insertAt(text, 6, "end").text)
        assertEquals("a\nb\nend\n", TextEdit.insertAt("a\nb", 3, "end").text)
        assertEquals("only\n", TextEdit.insertAt("", 1, "only").text)
        assertEquals("only\n", TextEdit.insertAt("", 2, "only").text)
    }

    @Test
    fun `a line outside the file or empty content is refused`() {
        assertThrows<ToolException> { TextEdit.insertAt(text, 0, "x") }
        assertThrows<ToolException> { TextEdit.insertAt(text, 7, "x") }
        assertThrows<ToolException> { TextEdit.insertAt(text, 2, "") }
    }
}
