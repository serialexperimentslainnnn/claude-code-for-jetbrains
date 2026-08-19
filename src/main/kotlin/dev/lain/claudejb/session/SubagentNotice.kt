package dev.lain.claudejb.session

object SubagentNotice {

    private const val MAX = 120

    private const val ORNAMENT = "#*->"

    fun headline(summary: String): String? {
        val line = summary.lineSequence()
            .map { it.trim { c -> c in ORNAMENT || c.isWhitespace() } }
            .firstOrNull { it.isNotBlank() }
            ?: return null
        if (line.length <= MAX) return line
        val cut = line.take(MAX).lastIndexOf(' ').takeIf { it > MAX / 2 } ?: MAX
        return line.take(cut).trimEnd() + "…"
    }
}
