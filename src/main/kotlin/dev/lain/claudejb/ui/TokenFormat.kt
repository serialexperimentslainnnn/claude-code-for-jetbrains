package dev.lain.claudejb.ui

import java.util.Locale

/**
 * Single source of truth for the compact human token count shown across the composer ("940", "1.2k", "3.4M").
 * Both [SessionUsagePanel] and [SubagentTasksPanel] delegate here so their formatting can't drift (one used to
 * truncate, the other rounded — diverging at the same input). Rounds to one decimal, dropping a trailing `.0`;
 * negative inputs clamp to 0.
 */
object TokenFormat {

    private const val THOUSAND = 1_000.0
    private const val MILLION = 1_000_000.0

    fun format(tokens: Long): String {
        val v = tokens.coerceAtLeast(0)
        return when {
            v < THOUSAND -> v.toString()
            v < MILLION -> trimDecimal(v / THOUSAND) + "k"
            else -> trimDecimal(v / MILLION) + "M"
        }
    }

    /**
     * [Locale.ROOT] twice over. Under a comma-decimal locale the default-locale format yields `1,2`, so the
     * label reads `1,2k` in an otherwise English UI — and, worse, the `.0` test below stops matching, so a flat
     * `1000` renders as `1,0k` instead of `1k`. The formatting and the string test have to agree on the
     * separator, and the only way to guarantee that is to pin it.
     */
    private fun trimDecimal(d: Double): String {
        val s = String.format(Locale.ROOT, "%.1f", d)
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }
}
