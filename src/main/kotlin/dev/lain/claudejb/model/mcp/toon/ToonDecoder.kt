package dev.lain.claudejb.model.mcp.toon

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal class ToonDecoder(text: String, options: ToonOptions) {

    private val strict = options.strict
    private val cursor = ToonCursor(ToonLines.of(text, options), strict)
    private val tables = ToonTables(cursor, strict)

    fun decode(): JsonElement {
        cursor.skipBlanks()
        val first = cursor.peek() ?: return JsonObject(emptyMap())
        return rootForm(first) ?: objectBody(0, LinkedHashMap())
    }

    private fun rootForm(first: Line): JsonElement? {
        if (first.depth != 0) return null
        val header = if (first.content == EMPTY_ARRAY) null else ToonHeader.parse(first.content, strict)
        val value = when {
            first.content == EMPTY_ARRAY -> JsonArray(emptyList()).also { cursor.next() }

            header != null && header.key == null -> {
                cursor.next()
                headerValue(header, 0)
            }

            header == null && ToonScan.indexOfUnquoted(first.content, ':') < 0 -> return rootPrimitive(first)

            else -> return null
        }
        cursor.skipBlanks()
        val trailing = cursor.peek()
        if (trailing != null && strict) toonError("content after the root form at line ${trailing.number}")
        return value
    }

    private fun rootPrimitive(line: Line): JsonElement {
        cursor.next()
        cursor.skipBlanks()
        val trailing = cursor.peek()
        if (trailing != null) toonError("a scalar line is valid only alone at the root; line ${trailing.number} follows one")
        return ToonText.value(line.content)
    }

    private fun objectBody(depth: Int, into: LinkedHashMap<String, JsonElement>): JsonObject {
        while (true) {
            cursor.skipBlanks()
            val line = cursor.peek()?.takeIf { it.depth >= depth } ?: break
            if (line.depth > depth) {
                orphan(line)
            } else {
                cursor.next()
                field(line.content, depth, into)
            }
        }
        return JsonObject(into)
    }

    private fun orphan(line: Line) {
        if (strict || ToonScan.indexOfUnquoted(line.content, ':') < 0) toonError("line ${line.number} belongs to no scope")
        cursor.next()
    }

    private fun field(content: String, depth: Int, into: LinkedHashMap<String, JsonElement>) {
        val header = ToonHeader.parse(content, strict)
        if (header != null) {
            val key = header.key ?: toonError("keyless header in object position: $content")
            putField(into, key, headerValue(header, depth), strict)
            return
        }
        val colon = ToonScan.indexOfUnquoted(content, ':')
        if (colon < 0) toonError("missing colon in: $content")
        val key = ToonText.keyOf(ToonScan.trimSpaces(content.substring(0, colon)))
        val rest = ToonScan.trimSpaces(content.substring(colon + 1))
        val value = when (rest) {
            "" -> objectBody(depth + 1, LinkedHashMap())
            EMPTY_ARRAY -> JsonArray(emptyList())
            else -> ToonText.value(rest)
        }
        putField(into, key, value, strict)
    }

    private fun headerValue(header: Header, depth: Int): JsonElement = when {
        header.keyed -> tables.keyed(header, depth)
        header.fields != null -> tables.tabular(header, depth)
        header.inline.isNotEmpty() -> inlineArray(header)
        else -> listItems(header, depth)
    }

    private fun inlineArray(header: Header): JsonArray {
        val values = ToonScan.split(header.inline, header.delimiter).map(ToonText::value)
        if (strict && values.size != header.length) toonError("expected ${header.length} values, found ${values.size}")
        return JsonArray(values)
    }

    private fun listItems(header: Header, depth: Int): JsonArray {
        val items = ArrayList<JsonElement>()
        var started = false
        while (true) {
            val body = nextItem(depth) ?: break
            if (!started) {
                started = true
                cursor.enterSpan(depth)
            }
            items += listItem(body, depth + 1)
        }
        if (started) cursor.leaveSpan()
        if (strict && items.size != header.length) toonError("expected ${header.length} items, found ${items.size}")
        return JsonArray(items)
    }

    private fun nextItem(depth: Int): String? {
        cursor.skipBlanks()
        val line = cursor.peek()?.takeIf { it.depth == depth + 1 } ?: return null
        val body = ToonScan.listItemContent(line.content) ?: return null
        cursor.next()
        return body
    }

    private fun listItem(body: String, depth: Int): JsonElement {
        if (body.isEmpty()) return JsonObject(emptyMap())
        if (body == EMPTY_ARRAY) return JsonArray(emptyList())
        val header = ToonHeader.parse(body, strict)
        if (header != null && header.key == null) {
            if (header.fields != null) toonError("a keyless fields-bearing header is valid only at the root: $body")
            return headerValue(header, depth)
        }
        if (header == null && ToonScan.indexOfUnquoted(body, ':') < 0) return ToonText.value(body)
        val into = LinkedHashMap<String, JsonElement>()
        field(body, depth + 1, into)
        return objectBody(depth + 1, into)
    }

    private companion object {
        const val EMPTY_ARRAY = "[]"
    }
}
