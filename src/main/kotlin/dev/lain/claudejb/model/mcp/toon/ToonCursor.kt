package dev.lain.claudejb.model.mcp.toon

internal class ToonCursor(private val lines: List<Line>, private val strict: Boolean) {

    private var index = 0
    private val spans = ArrayList<Int>()

    fun peek(): Line? = lines.getOrNull(index)

    fun next(): Line = lines[index++]

    fun skipBlanks() {
        var skipped = false
        while (peek()?.blank == true) {
            index++
            skipped = true
        }
        val next = peek() ?: return
        val outer = spans.firstOrNull() ?: return
        if (skipped && strict && next.depth > outer) toonError("blank line inside the scope that continues at line ${next.number}")
    }

    fun enterSpan(depth: Int) {
        spans += depth
    }

    fun leaveSpan() {
        spans.removeAt(spans.lastIndex)
    }
}
