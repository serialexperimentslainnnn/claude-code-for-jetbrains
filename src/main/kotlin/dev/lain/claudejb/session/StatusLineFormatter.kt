package dev.lain.claudejb.session

/**
 * Pure formatting for the composer status line. Kept separate from the Swing panel so the bucketing/rounding is
 * unit-testable without a UI.
 */
object StatusLineFormatter {

    /** Below this many thousands the compact form keeps one decimal ("1.2k"); above it, whole thousands ("23k"). */
    private const val DECIMAL_K_LIMIT = 10

    /** Sub-1000 counts are rounded to this step, so a live estimate ticking up doesn't re-render every delta. */
    private const val ROUNDING_STEP = 50

    /**
     * A compact suffix for the live reasoning-token estimate (system/thinking_tokens), or "" when there's nothing
     * to show. Rounded to a coarse bucket so the label doesn't flicker on every delta:
     * 0 → "", 850 → "~850 reasoning tokens", 1240 → "~1.2k reasoning tokens", 23800 → "~23k reasoning tokens".
     */
    fun thinkingSuffix(tokens: Int): String {
        if (tokens <= 0) return ""
        return "~${compact(tokens)} reasoning tokens"
    }

    private fun compact(n: Int): String = when {
        n >= 1000 -> {
            val k = n / 1000.0
            // Locale.ROOT, not the default locale: this string is embedded in fixed English UI text
            // ("~1.5k reasoning tokens"), and the default locale renders it "1,5k" on a Spanish or German
            // machine — a comma decimal separator inside an English sentence. The plugin is English-only by
            // decision (ADR 0003), so the number formatting follows the text, not the machine.
            if (k < DECIMAL_K_LIMIT) String.format(java.util.Locale.ROOT, "%.1fk", k) else "${n / 1000}k"
        }

        else -> roundTo(n, ROUNDING_STEP).toString()
    }

    private fun roundTo(n: Int, step: Int): Int = ((n + step / 2) / step) * step
}
