package dev.lain.claudejb.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkdownRendererTest {

    private fun html(md: String) = MarkdownRenderer.toHtml(md)

    @Test
    fun `headings and emphasis use Swing-renderable tags`() {
        val out = html("# Title\n\nplain **bold** and *italic*")
        assertTrue(out.contains("<h1>Title</h1>"), out)
        assertTrue(out.contains("<strong>bold</strong>"), out)
        assertTrue(out.contains("<em>italic</em>"), out)
    }

    @Test
    fun `link is not double-linkified`() {
        val out = html("[Enlace](https://example.com)")
        // The historic bug nested an <a> inside the href attribute; assert a single clean anchor.
        assertEquals(1, Regex("<a ").findAll(out).count(), out)
        assertTrue(out.contains("<a href=\"https://example.com\">Enlace</a>"), out)
    }

    @Test
    fun `disallowed link scheme renders as plain text, not an anchor`() {
        val out = html("[click](javascript:alert(1))")
        assertFalse(out.contains("<a "), out)
        assertFalse(out.contains("javascript:"), out)
        assertTrue(out.contains("click"), out)
    }

    @Test
    fun `allow-listed schemes still produce a single anchor`() {
        assertTrue(html("[h](http://x)").contains("<a href=\"http://x\">h</a>"), "http")
        assertTrue(html("[h](https://x)").contains("<a href=\"https://x\">h</a>"), "https")
        assertTrue(html("[h](jb://open?file=A&line=1)").contains("href=\"jb://open"), "jb")
    }

    @Test
    fun `quote in link url is escaped so it cannot break out of the href attribute`() {
        // No whitespace (the link regex stops at spaces); a bare quote inside the URL must be neutralized.
        val out = html("[x](https://a\"b)")
        assertTrue(out.contains("&quot;"), out)
        assertFalse(out.contains("href=\"https://a\"b\""), out)
    }

    @Test
    fun `bare url is autolinked once`() {
        val out = html("see https://bare.url here")
        assertTrue(out.contains("<a href=\"https://bare.url\">https://bare.url</a>"), out)
    }

    @Test
    fun `strikethrough becomes a strike tag`() {
        val out = html("~~gone~~")
        assertTrue(out.contains("<strike>gone</strike>"), out)
        assertFalse(out.contains("user-del"), out)
        assertFalse(out.contains("~~"), out)
    }

    @Test
    fun `task list checkboxes become glyphs`() {
        val out = html("- [ ] todo\n- [x] done")
        assertTrue(out.contains("&#9744;"), out) // unchecked
        assertTrue(out.contains("&#9745;"), out) // checked
        assertFalse(out.contains("<input"), out)
    }

    @Test
    fun `nested lists are preserved`() {
        val out = html("- a\n  - b")
        assertTrue(out.contains("<li>a<ul><li>b</li></ul></li>"), out)
    }

    @Test
    fun `gfm table emits rows`() {
        val out = html("| A | B |\n|---|---|\n| 1 | 2 |")
        assertTrue(out.contains("<th>A</th>"), out)
        assertTrue(out.contains("<td>1</td>"), out)
    }

    @Test
    fun `code span content is html-escaped`() {
        val out = html("`a < b & c`")
        assertTrue(out.contains("<code>a &lt; b &amp; c</code>"), out)
    }

    @Test
    fun `unterminated fence during streaming does not throw`() {
        // A code fence still being streamed must degrade gracefully, not break rendering.
        val out = html("```kotlin\nfun main() {")
        assertTrue(out.contains("<pre>"), out)
    }
}
