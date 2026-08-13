package dev.lain.claudejb.session

/**
 * The one line a finished subagent gets in the MAIN transcript.
 *
 * `task_notification.summary` is not a summary: for a subagent it carries the agent's entire final answer —
 * headings, tables, code blocks, thousands of characters. Rendering it here dumped a whole report into the
 * middle of the conversation, which on a session running a dozen agents is the same unreadability the
 * per-agent tabs were built to end. The report is not lost: it is the last thing in that agent's own
 * transcript, one click away on its tab.
 *
 * So this row is a POINTER, not the content — the first meaningful line, capped.
 */
object SubagentNotice {

    /** Roughly one line at the transcript's width. Long enough to identify the agent, short enough to skim. */
    private const val MAX = 120

    /** Markdown that carries no meaning once the text is rendered as a plain row: heading, bullet, quote, emphasis. */
    private const val ORNAMENT = "#*->"

    /**
     * The first non-blank line of [summary], stripped of markdown ornament and capped at [MAX] characters on a
     * word boundary, or null when there is nothing worth showing.
     */
    fun headline(summary: String): String? {
        // BOTH ends. Trimming only the start left `**bold**` as `bold**`, i.e. markdown leaking into a row
        // that is deliberately rendered as plain text — the emphasis markers have nothing to emphasise here.
        val line = summary.lineSequence()
            .map { it.trim { c -> c in ORNAMENT || c.isWhitespace() } }
            .firstOrNull { it.isNotBlank() }
            ?: return null
        if (line.length <= MAX) return line
        // Cut on a space so the row never ends mid-word; fall back to a hard cut for a line with no spaces
        // (a path, a URL), which would otherwise come out uncut and blow the width anyway.
        val cut = line.take(MAX).lastIndexOf(' ').takeIf { it > MAX / 2 } ?: MAX
        return line.take(cut).trimEnd() + "…"
    }
}
