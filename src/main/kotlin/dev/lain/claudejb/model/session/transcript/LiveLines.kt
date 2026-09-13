package dev.lain.claudejb.model.session.transcript

class LiveLines {

    private val lines = ArrayDeque<String>()
    private var dropped = 0

    fun add(line: String) {
        lines.addLast(line.take(MAX_LINE_CHARS))
        if (lines.size > MAX_LINES) {
            lines.removeFirst()
            dropped++
        }
    }

    fun render(): String {
        val body = lines.joinToString("\n")
        return if (dropped == 0) body else "… $dropped earlier lines not shown\n$body"
    }

    private companion object {
        const val MAX_LINES = 200
        const val MAX_LINE_CHARS = 400
    }
}
