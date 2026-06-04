package dev.lain.claudejb.ui

/**
 * Single source of truth for the compact human token count shown across the composer ("940", "1.2k", "3.4M").
 * Both [SessionUsagePanel] and [SubagentTasksPanel] delegate here so their formatting can't drift (one used to
 * truncate, the other rounded — diverging at the same input). Rounds to one decimal, dropping a trailing `.0`;
 * negative inputs clamp to 0.
 */
object TokenFormat {

    fun format(tokens: Long): String {
        val v = tokens.coerceAtLeast(0)
        return when {
            v < 1_000 -> v.toString()
            v < 1_000_000 -> trimDecimal(v / 1_000.0) + "k"
            else -> trimDecimal(v / 1_000_000.0) + "M"
        }
    }

    private fun trimDecimal(d: Double): String {
        val s = "%.1f".format(d)
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }
}
