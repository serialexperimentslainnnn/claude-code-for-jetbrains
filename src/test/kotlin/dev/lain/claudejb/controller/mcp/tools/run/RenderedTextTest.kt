package dev.lain.claudejb.controller.mcp.tools.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RenderedTextTest {

    @Test
    fun `a presentation is flattened in render order, strings quoted and comments spaced`() {
        val text = RenderedText().apply {
            renderKeywordValue("new")
            renderSpecialSymbol(" ")
            renderValue("Point")
            renderSpecialSymbol("(")
            renderNumericValue("3")
            renderSpecialSymbol(", ")
            renderStringValue("y")
            renderSpecialSymbol(")")
            renderComment("cached")
        }.text
        assertEquals("new Point(3, \"y\") cached", text)
    }

    @Test
    fun `a long string is cut at the renderer's limit and still quoted`() {
        val text = RenderedText().apply { renderStringValue("abcdef", null, 3) }.text
        assertEquals("\"abc…\"", text)
        assertEquals("\"abc\"", RenderedText().apply { renderStringValue("abc", "", 3) }.text)
    }

    @Test
    fun `an error and an empty presentation render as plain text`() {
        assertEquals("boom", RenderedText().apply { renderError("boom") }.text)
        assertEquals("", RenderedText().text)
    }
}
