package dev.lain.claudejb.util

object LogRing {

    enum class Level { WARN, INFO, DEBUG }

    data class Line(val seq: Long, val at: Long, val level: Level, val category: String, val text: String)

    data class Snapshot(val lines: List<Line>, val reset: Boolean, val dropped: Long)

    const val MAX_LINES = 2000

    private val lines = ArrayDeque<Line>()
    private var nextSeq = 0L
    private var dropped = 0L

    fun add(level: Level, category: String, text: String, at: Long = System.currentTimeMillis()): Line {
        synchronized(lines) {
            val line = Line(nextSeq++, at, level, category, text)
            lines.addLast(line)
            while (lines.size > MAX_LINES) {
                lines.removeFirst()
                dropped++
            }
            return line
        }
    }

    fun since(seq: Long): Snapshot = synchronized(lines) {
        val oldest = lines.firstOrNull()?.seq
        val reset = seq < 0 || (oldest != null && seq < oldest - 1)
        val fresh = if (reset) lines.toList() else lines.filter { it.seq > seq }
        Snapshot(fresh, reset, dropped)
    }

    fun snapshot(minLevel: Level = Level.DEBUG): List<Line> = synchronized(lines) {
        lines.filter { it.level.ordinal <= minLevel.ordinal }
    }

    fun size(): Int = synchronized(lines) { lines.size }

    fun clear() = synchronized(lines) {
        lines.clear()
        nextSeq = 0L
        dropped = 0L
    }
}
