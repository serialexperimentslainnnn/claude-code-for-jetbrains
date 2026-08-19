package dev.lain.claudejb.session

object StatusLineFormatter {

    private const val DECIMAL_K_LIMIT = 10

    private const val ROUNDING_STEP = 50

    fun thinkingSuffix(tokens: Int): String {
        if (tokens <= 0) return ""
        return "~${compact(tokens)} reasoning tokens"
    }

    private fun compact(n: Int): String = when {
        n >= 1000 -> {
            val k = n / 1000.0
            if (k < DECIMAL_K_LIMIT) String.format(java.util.Locale.ROOT, "%.1fk", k) else "${n / 1000}k"
        }

        else -> roundTo(n, ROUNDING_STEP).toString()
    }

    private fun roundTo(n: Int, step: Int): Int = ((n + step / 2) / step) * step
}
