package dev.lain.claudejb.model.mcp.toon

internal object ToonHeader {

    fun parse(content: String, strict: Boolean): Header? {
        val open = ToonScan.indexOfUnquoted(content, '[')
        if (open < 0) return null
        val colon = ToonScan.indexOfUnquoted(content, ':')
        if (colon in 0 until open) return null
        val rawKey = content.substring(0, open)
        val close = content.indexOf(']', open)
        val segment = if (close < 0) null else segment(content.substring(open + 1, close))
        if (rawKey != ToonScan.trimSpaces(rawKey) || segment == null) {
            return reject(strict, "malformed bracket segment in: $content")
        }
        return afterSegment(content, rawKey, segment, close + 1, strict)
    }

    private fun afterSegment(content: String, rawKey: String, segment: Segment, start: Int, strict: Boolean): Header? {
        var at = start
        var fields: List<Field>? = null
        if (content.getOrNull(at) == '{') {
            val end = ToonScan.matchingBrace(content, at)
            if (end < 0) return reject(strict, "unmatched brace in the field list of: $content")
            fields = fields(content.substring(at + 1, end), segment.delimiter, strict)
                ?: return reject(strict, "malformed field list in: $content")
            at = end + 1
        }
        if (content.getOrNull(at) != ':') return reject(strict, "content between the bracket segment and the colon in: $content")
        if (segment.keyed && fields == null) return reject(strict, "keyed header without a field list: $content")
        val inline = ToonScan.trimSpaces(content.substring(at + 1))
        if (fields != null && inline.isNotEmpty()) return reject(strict, "inline content after a fields-bearing header: $content")
        val key = rawKey.takeIf { it.isNotEmpty() }?.let(ToonText::keyOf)
        return Header(key, segment.length, segment.delimiter, segment.keyed, fields, inline)
    }

    fun render(fields: List<Field>, delimiter: Char): String =
        fields.joinToString(delimiter.toString()) { field ->
            ToonText.key(field.name) + (field.children?.let { "{" + render(it, delimiter) + "}" } ?: "")
        }

    fun leaves(fields: List<Field>): Int = fields.sumOf { it.children?.let(::leaves) ?: 1 }

    private fun reject(strict: Boolean, message: String): Header? = if (strict) toonError(message) else null

    private fun segment(text: String): Segment? {
        var end = 0
        while (end < text.length && text[end] in '0'..'9') end++
        if (end == 0 || (text[0] == '0' && end > 1)) return null
        val length = text.substring(0, end).toIntOrNull() ?: return null
        var rest = text.substring(end)
        val keyed = rest.startsWith(':')
        if (keyed) rest = rest.substring(1)
        val delimiter = when (rest) {
            "" -> ','
            "\t" -> '\t'
            "|" -> '|'
            else -> return null
        }
        return Segment(length, keyed, delimiter)
    }

    private fun fields(text: String, delimiter: Char, strict: Boolean): List<Field>? {
        val out = ArrayList<Field>()
        for (entry in entries(text, delimiter)) {
            val brace = ToonScan.indexOfUnquoted(entry, '{')
            val raw = ToonScan.trimSpaces(if (brace < 0) entry else entry.substring(0, brace))
            if (raw.isEmpty() || mismatched(raw, delimiter)) return null
            val children = if (brace < 0) null else nested(entry, brace, delimiter, strict) ?: return null
            val name = ToonText.keyOf(raw)
            if (strict && out.any { it.name == name }) toonError("duplicate field name $name in {$text}")
            out += Field(name, children)
        }
        return out
    }

    private fun nested(entry: String, brace: Int, delimiter: Char, strict: Boolean): List<Field>? {
        val end = ToonScan.matchingBrace(entry, brace)
        if (end != entry.lastIndex) return null
        return fields(entry.substring(brace + 1, end), delimiter, strict)
    }

    private fun mismatched(name: String, delimiter: Char): Boolean =
        !name.startsWith('"') && name.any { it in DELIMITERS && it != delimiter }

    private fun entries(text: String, delimiter: Char): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var depth = 0
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '\\' -> i++

                c == '"' -> quoted = !quoted

                quoted -> Unit

                c == '{' -> depth++

                c == '}' -> depth--

                c == delimiter && depth == 0 -> {
                    out += ToonScan.trimSpaces(text.substring(start, i))
                    start = i + 1
                }
            }
            i++
        }
        out += ToonScan.trimSpaces(text.substring(start))
        return out
    }

    private class Segment(val length: Int, val keyed: Boolean, val delimiter: Char)

    private const val DELIMITERS = ",\t|"
}

internal class Field(val name: String, val children: List<Field>?)

internal class Header(
    val key: String?,
    val length: Int,
    val delimiter: Char,
    val keyed: Boolean,
    val fields: List<Field>?,
    val inline: String,
) {
    val leafCount: Int get() = fields?.let(ToonHeader::leaves) ?: 0
}
