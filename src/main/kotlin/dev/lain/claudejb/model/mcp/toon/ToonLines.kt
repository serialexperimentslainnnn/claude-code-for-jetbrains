package dev.lain.claudejb.model.mcp.toon

internal object ToonLines {

    fun of(text: String, options: ToonOptions): List<Line> =
        text.removePrefix(BOM).split('\n').mapIndexedNotNull { index, raw ->
            line(index + 1, raw.removeSuffix("\r").trimEnd(' '), options)
        }

    private fun line(number: Int, raw: String, options: ToonOptions): Line? {
        var spaces = 0
        while (spaces < raw.length && raw[spaces] == ' ') spaces++
        if (raw.getOrNull(spaces) == '#') return null
        if (raw.all { it == ' ' || it == '\t' }) return Line(number, 0, "")
        var tabs = 0
        while (raw.getOrNull(spaces + tabs) == '\t') tabs++
        if (options.strict && tabs > 0) toonError("tab in the indentation of line $number")
        if (options.strict && spaces % options.indentSize != 0) {
            toonError("indentation of line $number is not a multiple of ${options.indentSize}")
        }
        return Line(number, spaces / options.indentSize + tabs, raw.substring(spaces + tabs))
    }

    private const val BOM = "﻿"
}

internal class Line(val number: Int, val depth: Int, val content: String) {
    val blank: Boolean get() = content.isEmpty()
}
