package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.ui.CodeHighlighter

/**
 * Headless: [CodeHighlighter] maps fence languages to file types and produces colored `<pre>` HTML via the
 * IDE's real lexers. The lang→filename mapping and fence masking are pure; the actual highlight needs the
 * platform's FileTypeManager/lexers, hence [BasePlatformTestCase].
 */
class CodeHighlighterHeadlessTest : BasePlatformTestCase() {

    fun `test filenameForLang maps known aliases and rejects unknown`() {
        assertEquals("a.kt", CodeHighlighter.filenameForLang("kotlin"))
        assertEquals("a.kt", CodeHighlighter.filenameForLang("KT"))      // case-insensitive
        assertEquals("a.py", CodeHighlighter.filenameForLang(" python ")) // trimmed
        assertEquals("a.js", CodeHighlighter.filenameForLang("js"))
        assertEquals("a.sh", CodeHighlighter.filenameForLang("bash"))
        assertNull(CodeHighlighter.filenameForLang("klingon"))
        assertNull(CodeHighlighter.filenameForLang(""))
    }

    fun `test maskHighlightableFences extracts only language-tagged closed fences`() {
        val md = """
            intro
            ```kotlin
            val x = 1
            ```
            mid
            ```
            no language
            ```
            ```klingon
            unknown
            ```
        """.trimIndent()
        val captured = ArrayList<Pair<String, String>>()
        val masked = CodeHighlighter.maskHighlightableFences(md) { lang, code ->
            captured.add(lang to code)
            captured.size - 1
        }
        // Only the kotlin block is masked; the no-language and unknown-language fences pass through verbatim.
        assertEquals(1, captured.size)
        assertEquals("kotlin", captured[0].first)
        assertEquals("val x = 1", captured[0].second)
        assertTrue("masked keeps placeholder", masked.contains("CB0"))
        assertTrue("plain fence preserved", masked.contains("no language"))
        assertTrue("unknown-language fence preserved", masked.contains("```klingon"))
        assertFalse("masked drops the highlighted code", masked.contains("val x = 1"))
    }

    fun `test unterminated fence is left for the renderer`() {
        val md = "```kotlin\nval x = 1\n" // no closing fence (streaming)
        var calls = 0
        val masked = CodeHighlighter.maskHighlightableFences(md) { _, _ -> calls++; 0 }
        assertEquals(0, calls)
        assertTrue(masked.contains("```kotlin"))
    }

    fun `test highlightToHtml tokenizes a known language without losing text`() {
        val html = CodeHighlighter.highlightToHtml("kotlin", "val x = 1")
        assertTrue("wrapped in pre", html.startsWith("<pre"))
        // Tokenization must preserve the source verbatim once tags/entities are stripped.
        val textOnly = html.substringAfter('>').substringBeforeLast("</pre>")
            .replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        assertEquals("val x = 1", textOnly)
        // NB: real colored <span>s require a populated editor color scheme; the bare test scheme may resolve no
        // foreground for tokens, so this asserts lossless tokenization rather than the presence of color.
    }

    fun `test highlightToHtml escapes html and falls back for unknown language`() {
        val html = CodeHighlighter.highlightToHtml("klingon", "a < b && c > d")
        assertTrue(html.startsWith("<pre"))
        assertTrue("angle brackets escaped", html.contains("&lt;") && html.contains("&gt;"))
        assertTrue("ampersand escaped", html.contains("&amp;"))
        assertFalse("no colored spans for unknown lang", html.contains("<span"))
    }
}
