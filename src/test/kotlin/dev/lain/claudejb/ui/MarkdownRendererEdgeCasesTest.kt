package dev.lain.claudejb.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Edge cases that complement [MarkdownRendererTest]: GFM-in-cells, partial fences from streaming,
 * mixed inline emphasis with strike, nested + task lists, and the no-double-linkify invariant under
 * adjacency (autolink + path:line + plain URL sitting next to each other on the same line).
 */
class MarkdownRendererEdgeCasesTest {

    private fun html(md: String) = MarkdownRenderer.toHtml(md)

    @Test
    fun `gfm table cells render inline bold and code spans`() {
        val out = html(
            """
            | Col | Value |
            |-----|-------|
            | **bold** | `code` |
            """.trimIndent()
        )
        assertTrue(out.contains("<td><strong>bold</strong></td>"), out)
        assertTrue(out.contains("<td><code>code</code></td>"), out)
    }

    @Test
    fun `gfm table cell with link renders a single anchor`() {
        val out = html(
            """
            | A | B |
            |---|---|
            | [click](https://example.com) | x |
            """.trimIndent()
        )
        assertTrue(out.contains("""<a href="https://example.com">click</a>"""), out)
        assertEquals(1, Regex("<a ").findAll(out).count(), out)
    }

    @Test
    fun `path-line inside a table cell becomes a jb open link`() {
        val out = html(
            """
            | File | Line |
            |------|------|
            | src/App.kt:42 | here |
            """.trimIndent()
        )
        assertTrue(out.contains("""href="jb://open?file="""), out)
        assertTrue(out.contains("&amp;line=42"), out)
    }

    @Test
    fun `unterminated fence at end of input degrades to a pre block (no exception)`() {
        val out = html("Intro\n\n```kotlin\nfun foo() {\n  return 1") // never closes
        assertTrue(out.contains("<pre><code>"), out)
        // Content survives, even truncated; escape() runs over it.
        assertTrue(out.contains("fun foo()"), out)
    }

    @Test
    fun `nested list mixing bullets and task items renders correctly`() {
        val out = html("- [ ] outer\n  - [x] inner\n  - [ ] also inner")
        // Outer is a task item with the unchecked glyph; the nested ul contains two task items.
        assertTrue(out.contains("&#9744; outer"), out)
        assertTrue(out.contains("&#9745; inner"), out)
        assertTrue(out.contains("&#9744; also inner"), out)
        // Structurally a nested <ul>.
        assertTrue(out.contains("<ul><li>"), out)
        assertTrue(Regex("<li>[^<]*&#9744; outer<ul>").containsMatchIn(out), out)
    }

    @Test
    fun `code span with angle brackets is html-escaped (no tag injection)`() {
        val out = html("inline `<script>` span")
        assertTrue(out.contains("<code>&lt;script&gt;</code>"), out)
        assertFalse(out.contains("<script>"), out)
    }

    @Test
    fun `strike wrapping bold renders both layers without leaking markers`() {
        val out = html("~~**important**~~")
        assertTrue(out.contains("<strike>"), out)
        assertTrue(out.contains("<strong>important</strong>"), out)
        assertFalse(out.contains("~~"), out)
        assertFalse(out.contains("**"), out)
    }

    @Test
    fun `link with asterisks in the link text does not double-linkify`() {
        // The historic bug rewrote URLs inside an already-emitted anchor. With `**` in the link text
        // the bold pass must run, but no second <a> may appear and the anchor must stay well-formed.
        val out = html("[**emph**](https://example.com)")
        assertEquals(1, Regex("<a ").findAll(out).count(), out)
        assertTrue(out.contains("""<a href="https://example.com">"""), out)
    }

    @Test
    fun `autolink and path-line side-by-side each linkify exactly once`() {
        val out = html("see https://docs.example src/A.kt:10 end")
        val anchors = Regex("<a ").findAll(out).count()
        assertEquals(2, anchors, "expected exactly one anchor for the URL and one for the path:line; got\n$out")
        assertTrue(out.contains("""<a href="https://docs.example">"""), out)
        assertTrue(out.contains("""href="jb://open?file="""), out)
    }

    @Test
    fun `inline code containing a URL is not autolinked`() {
        val out = html("call `curl https://example.com/api`")
        // The URL is inside a code span → must NOT become an anchor.
        assertEquals(0, Regex("<a ").findAll(out).count(), out)
        assertTrue(out.contains("<code>curl https://example.com/api</code>"), out)
    }

    @Test
    fun `mixed bold italic and strike on the same line all render`() {
        val out = html("a **b** _c_ ~~d~~ e")
        assertTrue(out.contains("<strong>b</strong>"), out)
        assertTrue(out.contains("<em>c</em>"), out)
        assertTrue(out.contains("<strike>d</strike>"), out)
    }
}
