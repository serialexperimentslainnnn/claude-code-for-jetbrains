package dev.lain.claudejb.model.mcp.toon

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal class ToonTables(private val cursor: ToonCursor, private val strict: Boolean) {

    fun tabular(header: Header, depth: Int): JsonArray {
        val fields = header.fields ?: toonError("tabular header without fields")
        val rows = ArrayList<JsonElement>()
        var started = false
        while (true) {
            cursor.skipBlanks()
            val line = cursor.peek() ?: break
            if (line.depth != depth + 1 || !isRow(line.content, header.delimiter)) break
            cursor.next()
            if (!started) {
                started = true
                cursor.enterSpan(depth)
            }
            rows += row(fields, ToonScan.split(line.content, header.delimiter), header.leafCount)
        }
        if (started) cursor.leaveSpan()
        if (strict && rows.size != header.length) toonError("expected ${header.length} rows, found ${rows.size}")
        return JsonArray(rows)
    }

    fun keyed(header: Header, depth: Int): JsonObject {
        val fields = header.fields ?: toonError("keyed header without fields")
        val out = LinkedHashMap<String, JsonElement>()
        var started = false
        var count = 0
        while (true) {
            val line = nextEntry(depth) ?: break
            if (!started) {
                started = true
                cursor.enterSpan(depth)
            }
            val (key, cells) = entry(line, header.delimiter) ?: continue
            putField(out, key, row(fields, cells, header.leafCount), strict)
            count++
        }
        if (started) cursor.leaveSpan()
        if (strict && count != header.length) toonError("expected ${header.length} entries, found $count")
        return JsonObject(out)
    }

    private fun nextEntry(depth: Int): Line? {
        while (true) {
            cursor.skipBlanks()
            val line = cursor.peek() ?: return null
            if (line.depth <= depth) return null
            cursor.next()
            if (line.depth == depth + 1) return line
            if (strict) toonError("line ${line.number} is over-indented for an entry row")
        }
    }

    private fun entry(line: Line, delimiter: Char): Pair<String, List<String>>? {
        val colon = ToonScan.indexOfUnquoted(line.content, ':')
        if (colon < 0) {
            if (strict) toonError("entry row without a colon at line ${line.number}")
            return null
        }
        val rest = ToonScan.trimSpaces(line.content.substring(colon + 1))
        val cells = if (rest.isEmpty()) emptyList() else ToonScan.split(rest, delimiter)
        return ToonText.keyOf(ToonScan.trimSpaces(line.content.substring(0, colon))) to cells
    }

    private fun isRow(content: String, delimiter: Char): Boolean {
        val colon = ToonScan.indexOfUnquoted(content, ':')
        val split = ToonScan.indexOfUnquoted(content, delimiter)
        return colon < 0 || (split in 0 until colon)
    }

    private fun row(fields: List<Field>, cells: List<String>, leafCount: Int): JsonObject {
        if (strict && cells.size != leafCount) toonError("expected $leafCount cells, found ${cells.size}")
        return build(fields, cells.iterator())
    }

    private fun build(fields: List<Field>, cells: Iterator<String>): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (field in fields) {
            if (field.children != null) {
                out[field.name] = build(field.children, cells)
            } else if (cells.hasNext()) {
                out[field.name] = ToonText.value(cells.next())
            }
        }
        return JsonObject(out)
    }
}

internal fun putField(into: MutableMap<String, JsonElement>, key: String, value: JsonElement, strict: Boolean) {
    if (strict && key in into) toonError("duplicate key $key")
    into[key] = value
}
