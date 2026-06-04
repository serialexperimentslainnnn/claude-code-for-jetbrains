package dev.lain.claudejb.ui

/**
 * A small, dependency-free Markdown → HTML converter producing markup the Swing [javax.swing.text.html.HTMLEditorKit]
 * can render: paragraphs, ATX headings, fenced/inline code, bold/italic, strikethrough, links, bullet & ordered
 * lists (nested, with GFM task-list checkboxes), block quotes, tables and horizontal rules. It is deliberately
 * lenient — assistant text arrives token by token, so half-written constructs (an unclosed ``` fence, a dangling
 * `**`) must degrade gracefully rather than break.
 *
 * Block backgrounds (code, quotes) are styled by the shared stylesheet in [HtmlContent]; this only emits structure.
 */
object MarkdownRenderer {

    fun toHtml(markdown: String): String {
        val lines = markdown.replace("\r\n", "\n").split("\n")
        val out = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // Fenced code block: ```lang … ``` (an unterminated fence runs to the end, for streaming).
            if (trimmed.startsWith("```")) {
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                if (i < lines.size) i++ // consume closing fence
                out.append("<pre><code>").append(escape(code.toString().trimEnd('\n'))).append("</code></pre>")
                continue
            }

            // Blank line: paragraph separator.
            if (trimmed.isEmpty()) {
                i++
                continue
            }

            // Horizontal rule.
            if (trimmed.matches(Regex("^([-*_])\\1{2,}$"))) {
                out.append("<hr>")
                i++
                continue
            }

            // ATX heading.
            val heading = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
            if (heading != null) {
                val level = heading.groupValues[1].length
                out.append("<h$level>").append(inline(heading.groupValues[2])).append("</h$level>")
                i++
                continue
            }

            // Block quote (consecutive '>' lines).
            if (trimmed.startsWith(">")) {
                out.append("<blockquote>")
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    out.append(inline(lines[i].trim().removePrefix(">").trim())).append("<br>")
                    i++
                }
                out.append("</blockquote>")
                continue
            }

            // GFM table: a header row (a line with '|') immediately followed by a |---|:--:| separator.
            if (line.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
                out.append("<table cellspacing=\"0\">")
                out.append("<tr>")
                splitRow(line).forEach { out.append("<th>").append(inline(it)).append("</th>") }
                out.append("</tr>")
                i += 2 // header + separator
                while (i < lines.size && lines[i].contains('|') && lines[i].trim().isNotEmpty()) {
                    out.append("<tr>")
                    splitRow(lines[i]).forEach { out.append("<td>").append(inline(it)).append("</td>") }
                    out.append("</tr>")
                    i++
                }
                out.append("</table>")
                continue
            }

            // Lists (bullet or ordered), with indentation-based nesting and GFM task-list checkboxes.
            if (isBullet(trimmed) || isOrdered(trimmed)) {
                val (html, next) = parseList(lines, i)
                out.append(html)
                i = next
                continue
            }

            // Paragraph: gather following non-blank, non-structural lines, soft-joined.
            val para = StringBuilder(line)
            i++
            while (i < lines.size && lines[i].trim().isNotEmpty() && !startsBlock(lines[i].trim())) {
                para.append(' ').append(lines[i].trim())
                i++
            }
            out.append("<p>").append(inline(para.toString())).append("</p>")
        }
        return out.toString()
    }

    private fun isBullet(s: String) = s.matches(Regex("^[-*+]\\s+.*"))
    private fun isOrdered(s: String) = s.matches(Regex("^\\d+\\.\\s+.*"))
    private fun isItem(s: String) = isBullet(s) || isOrdered(s)
    private fun indentOf(s: String) = s.takeWhile { it == ' ' }.length

    /** Schemes allowed in explicit `[text](url)` links; anything else renders as plain text (no href). */
    private val ALLOWED_LINK_SCHEME = Regex("^(?:https?|jb)://.*", RegexOption.IGNORE_CASE)
    private val markerRegex = Regex("^([-*+]|\\d+\\.)\\s+")
    private val taskRegex = Regex("^\\[([ xX])]\\s+(.*)$")

    /**
     * Parses a list (and its nested sub-lists) starting at [start], returning the HTML and the index of the first
     * line that is no longer part of it. Nesting is driven by leading-space indentation: a more-indented item run
     * becomes a child `<ul>/<ol>` of the preceding `<li>`. GFM task markers (`[ ]`/`[x]`) render as glyphs.
     */
    private fun parseList(lines: List<String>, start: Int): Pair<String, Int> {
        val baseIndent = indentOf(lines[start])
        val ordered = isOrdered(lines[start].trim())
        val sb = StringBuilder(if (ordered) "<ol>" else "<ul>")
        var i = start
        while (i < lines.size) {
            val t = lines[i].trim()
            if (t.isEmpty() || !isItem(t)) break
            val indent = indentOf(lines[i])
            if (indent < baseIndent) break // dedent: this item belongs to an outer list
            val content = t.replaceFirst(markerRegex, "")
            i++
            // A following, more-indented item run is this item's nested list.
            val nested = if (i < lines.size && isItem(lines[i].trim()) && indentOf(lines[i]) > baseIndent) {
                val (childHtml, next) = parseList(lines, i)
                i = next
                childHtml
            } else ""
            sb.append("<li>").append(renderItem(content)).append(nested).append("</li>")
        }
        sb.append(if (ordered) "</ol>" else "</ul>")
        return sb.toString() to i
    }

    /** Item body: a GFM checkbox becomes a glyph (☐/☑), everything else is rendered as inline Markdown. */
    private fun renderItem(content: String): String {
        val task = taskRegex.find(content) ?: return inline(content)
        val checked = task.groupValues[1].isNotBlank()
        return (if (checked) "&#9745; " else "&#9744; ") + inline(task.groupValues[2])
    }

    /** A GFM table delimiter row: every cell is dashes with optional leading/trailing ':' alignment. */
    private fun isTableSeparator(s: String): Boolean {
        val t = s.trim()
        if (!t.contains('-') || !t.contains('|')) return false
        return t.trim('|').split('|').let { cells ->
            cells.isNotEmpty() && cells.all { it.trim().matches(Regex("^:?-+:?$")) }
        }
    }

    /** Splits a table row into trimmed cells, dropping the optional leading/trailing pipe borders. */
    private fun splitRow(s: String): List<String> =
        s.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
    private fun startsBlock(s: String) =
        s.startsWith("```") || s.startsWith("#") || s.startsWith(">") || isBullet(s) || isOrdered(s) ||
            s.matches(Regex("^([-*_])\\1{2,}$"))

    /**
     * Inline spans: code, links, emphasis, strikethrough. Code spans and explicit links are masked with a
     * NUL-delimited placeholder first (NUL never occurs in assistant text and survives escape() and every
     * emphasis/autolink regex), so their contents aren't reparsed and a bare-url autolink can't re-link the URL
     * already inside an href — the historic double-linkify bug (<a href="<a href=…">).
     */
    private fun inline(raw: String): String {
        val codeSpans = ArrayList<String>()
        var masked = Regex("`([^`]+)`").replace(raw) { m ->
            codeSpans.add(m.groupValues[1])
            " ${codeSpans.size - 1} "
        }
        masked = escape(masked)
        val anchors = ArrayList<String>()
        masked = Regex("\\[([^\\]]+)]\\(([^)\\s]+)\\)").replace(masked) { m ->
            // Untrusted markup: only link an allow-listed scheme; escape '"' so a crafted URL can't break out of
            // the href (escape() already neutralized &/</> ). Other schemes (javascript:/file:/data:/relative) →
            // plain text, no href reaches the Swing HTML view.
            val url = m.groupValues[2]
            if (!ALLOWED_LINK_SCHEME.matches(url)) return@replace m.groupValues[1]
            anchors.add("<a href=\"${url.replace("\"", "&quot;")}\">${m.groupValues[1]}</a>")
            " a${anchors.size - 1} "
        }
        // Linkify bare source locations (`path/Foo.kt:42`) into an internal jb://open scheme the chat view
        // navigates with. Masked as an anchor so the http(s) autolink below can't touch the encoded href and
        // so the path/line text isn't reparsed as emphasis. Runs on already-escaped text; `&` in the file path
        // is re-escaped inside the URL-encoded query, so no raw '&' leaks into the HTML.
        // The line number is OPTIONAL (defaults to 1) so a bare `src/Foo.kt` is clickable too. In prose we only
        // link an obvious path (contains '/') or an explicit `path:line` — an explicit line is a strong enough
        // signal that it's a file reference, so we link it whatever the extension (`schema.graphql:42`); a bare
        // filename with no slash and no line is left alone so a product name like "Node.js" or a stray "config.json"
        // in a sentence doesn't become a dead link (code spans below stay permissive for path-like tokens).
        masked = Regex("(?<![\\w/])((?:[\\w.\\-]+/)*[\\w.\\-]+\\.[A-Za-z0-9]+)(?::(\\d+))?\\b").replace(masked) { m ->
            val path = m.groupValues[1]
            val lineGroup = m.groupValues[2]
            if (lineGroup.isEmpty() && !path.contains('/')) return@replace m.value
            val line = lineGroup.ifEmpty { "1" }
            val encoded = java.net.URLEncoder.encode(path, Charsets.UTF_8).replace("+", "%20")
            anchors.add("<a href=\"jb://open?file=$encoded&amp;line=$line\">${m.value}</a>")
            " a${anchors.size - 1} "
        }
        masked = Regex("(?<!\\w)(https?://[^\\s<]+)").replace(masked) { m -> "<a href=\"${m.value}\">${m.value}</a>" }
        masked = Regex("~~([^~]+)~~").replace(masked) { "<strike>${it.groupValues[1]}</strike>" }
        masked = Regex("\\*\\*([^*]+)\\*\\*").replace(masked) { "<strong>${it.groupValues[1]}</strong>" }
        masked = Regex("__([^_]+)__").replace(masked) { "<strong>${it.groupValues[1]}</strong>" }
        masked = Regex("(?<![*\\w])\\*([^*\\n]+)\\*(?![*\\w])").replace(masked) { "<em>${it.groupValues[1]}</em>" }
        masked = Regex("(?<![_\\w])_([^_\\n]+)_(?![_\\w])").replace(masked) { "<em>${it.groupValues[1]}</em>" }
        // Restore masked anchors (NUL + 'a' + idx + NUL), then code spans (NUL + idx + NUL) as escaped <code>.
        masked = Regex(" a(\\d+) ").replace(masked) { anchors[it.groupValues[1].toInt()] }
        masked = Regex(" (\\d+) ").replace(masked) { m ->
            val content = codeSpans[m.groupValues[1].toInt()]
            // path:line references the model puts in `…` (very common in code answers) stay monospaced AND
            // become jb://open links so jump-to-code works inside backticks too — without this, the code-span
            // masking above hides them from the bare-text linkifier and they render as inert <code> text.
            val pathLine = CODESPAN_PATH_LINE.matchEntire(content)
            if (pathLine != null && looksLikePath(pathLine.groupValues[1])) {
                val path = pathLine.groupValues[1]
                val line = pathLine.groupValues[2].ifEmpty { "1" }
                val encoded = java.net.URLEncoder.encode(path, Charsets.UTF_8).replace("+", "%20")
                "<code><a href=\"jb://open?file=$encoded&amp;line=$line\">${escape(content)}</a></code>"
            } else {
                "<code>${escape(content)}</code>"
            }
        }
        return masked
    }

    /** Anchored variant of the path regex, used to detect file references hidden inside code spans. The `:line` is
     *  optional (a bare `src/Foo.kt` in backticks still links, at line 1). */
    private val CODESPAN_PATH_LINE = Regex("^((?:[\\w.\\-]+/)*[\\w.\\-]+\\.[A-Za-z0-9]+)(?::(\\d+))?$")

    /** File extensions we treat as a real source/text path, so a path-like token links to jb://open. Keeps generic
     *  dotted tokens ("Node.js" stripped of slash, "obj.method") from becoming dead links unless they clearly look
     *  like a file (a slash, or one of these extensions). */
    private val SOURCE_EXT = setOf(
        "kt", "kts", "java", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "json", "json5", "yaml", "yml",
        "xml", "html", "htm", "css", "scss", "less", "sql", "go", "rs", "c", "cc", "cpp", "h", "hpp", "cs",
        "rb", "php", "swift", "sh", "bash", "zsh", "md", "markdown", "gradle", "properties", "toml", "groovy",
        "txt", "cfg", "ini", "env", "lua", "pl", "r", "scala", "dart", "vue", "svelte",
    )

    /** True when [token] looks like a real file path: it contains a directory separator, or ends in a known source
     *  extension — the two signals that distinguish `src/App.tsx`/`build.gradle.kts` from prose like "e.g." or "Vue.js". */
    private fun looksLikePath(token: String): Boolean =
        token.contains('/') || token.substringAfterLast('.', "").lowercase() in SOURCE_EXT

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
