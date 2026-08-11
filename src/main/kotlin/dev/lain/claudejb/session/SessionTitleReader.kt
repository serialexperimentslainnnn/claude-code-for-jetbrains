package dev.lain.claudejb.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Reads the human-readable session title the `claude` binary generates (the one shown by `--resume`)
 * from its sidecar transcript. The binary persists, per session, lines like
 * `{"type":"ai-title","aiTitle":"…","sessionId":"…"}` and — after a `/rename` — a `customTitle`.
 *
 * File access is delegated to [SessionStore] (the single source of truth, confined to
 * `~/.claude/projects`); IO is synchronous and must run off the EDT. The title-selection logic is
 * factored into the pure [pickTitle] so it is unit-testable without a filesystem.
 */
object SessionTitleReader {

    @Serializable
    private data class TitleLine(
        val type: String? = null,
        val aiTitle: String? = null,
        val customTitle: String? = null,
    )

    private val JSON = Json { ignoreUnknownKeys = true }

    /** Returns the binary's title for [sessionId], or null if no sidecar / no title line is found. */
    fun readTitle(sessionId: String): String? =
        SessionStore.readLines(sessionId)?.let { pickTitle(it) }

    /**
     * Picks the session title from raw JSONL lines, in order of authority: the last non-blank `customTitle`
     * (the user's own `/rename`), then the last `ai-title` line, then **the first thing the user actually
     * asked**. Blank/corrupt lines are skipped, never throw. Pure — unit-testable.
     *
     * **Why the first prompt is in here at all.** `claude` 2.1.226 does not write an `ai-title` line —
     * checked across every session on this machine, in every project: not one. So a chat stayed "Chat 3"
     * for its whole life, which is exactly as useful as no title. The binary itself falls back the same
     * way (it keeps `lastSessionFirstPrompt` in `~/.claude.json`), and the first prompt is a genuinely good
     * name for a conversation: it is what you went there to do. If the binary starts emitting titles again,
     * rule 2 wins on its own and this never applies.
     */
    fun pickTitle(lines: List<String>): String? {
        var custom: String? = null
        var ai: String? = null
        var firstPrompt: String? = null
        for (line in lines) {
            if (line.isBlank()) continue
            val parsed = runCatching { JSON.decodeFromString<TitleLine>(line) }.getOrNull() ?: continue
            parsed.customTitle?.takeIf { it.isNotBlank() }?.let { custom = it }
            if (parsed.type == "ai-title") parsed.aiTitle?.takeIf { it.isNotBlank() }?.let { ai = it }
            if (firstPrompt == null && parsed.type == "user") firstPrompt = firstPromptOf(line)
        }
        return custom ?: ai ?: firstPrompt
    }

    /**
     * The user's own words from a `user` line, as a tab-sized title, or null when the line is not the user
     * speaking.
     *
     * [SyntheticUserText] is what makes this usable: the binary writes its own bookkeeping on `user` lines
     * too — `<task-notification>` blocks, caveats, tool results — and titling a chat "Caveat: The messages
     * below were generated…" is worse than "Chat 3".
     */
    private fun firstPromptOf(line: String): String? {
        val entries = runCatching { SessionTranscriptReader.parseEntries(listOf(line)) }.getOrNull().orEmpty()
        val text = entries.firstOrNull { it.speaker == "USER" }?.text?.trim().orEmpty()
        if (text.isBlank()) return null
        // One line, and short: a tab shows a couple of dozen characters before it truncates anyway, and the
        // full text is in the tooltip. Cut on a word boundary so it does not end mid-syllable.
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        if (firstLine.length <= MAX_TITLE) return firstLine
        val cut = firstLine.take(MAX_TITLE)
        return (cut.substringBeforeLast(' ', cut).trimEnd(',', '.', ';', ':') + "…")
    }

    /** Long enough to tell two conversations apart, short enough that the tab is not all ellipsis. */
    private const val MAX_TITLE = 48
}
