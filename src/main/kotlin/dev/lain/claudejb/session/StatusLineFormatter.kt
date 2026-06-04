package dev.lain.claudejb.session

/**
 * Pure formatting for the composer status line. Kept separate from the Swing panel so the bucketing/rounding is
 * unit-testable without a UI.
 */
object StatusLineFormatter {

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
            if (k < 10) String.format("%.1fk", k) else "${n / 1000}k"
        }
        else -> roundTo(n, 50).toString()
    }

    private fun roundTo(n: Int, step: Int): Int = ((n + step / 2) / step) * step
}
