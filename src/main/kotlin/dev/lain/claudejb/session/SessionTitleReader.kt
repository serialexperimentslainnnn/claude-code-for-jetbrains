package dev.lain.claudejb.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a session's own file says the conversation is called, and whether anyone actually named it.
 *
 * [authored] is the field a caller acts on, and the reason this is a value rather than a bare string: `true`
 * when a `customTitle` (the user's `/rename`) or an `ai-title` line named this chat, `false` when [text] is
 * only the first-prompt fallback. Whether a generated title is still owed cannot be read off the text — the
 * fallback is never absent once the user has said anything, so a caller keyed on "is there a title" would ask
 * for one exactly never.
 */
data class SessionTitle(
    /** What the tab shows. */
    val text: String,
    /** Whether a `customTitle`/`ai-title` line decided [text], as opposed to the first-prompt fallback. */
    val authored: Boolean,
    /**
     * The first thing the user asked, whole and untruncated — the material a generated title summarises.
     *
     * Null when the session carries no user prompt yet (only the binary's own bookkeeping, or nothing at all).
     */
    val prompt: String?,
)

/**
 * Reads the human-readable session title the `claude` binary keeps (the one shown by `--resume`) from its
 * sidecar transcript. The binary persists, per session, lines like
 * `{"type":"ai-title","aiTitle":"…","sessionId":"…"}` and — after a `/rename` — a `customTitle`.
 *
 * File access is delegated to [SessionStore] (the single source of truth, confined to
 * `~/.claude/projects`); IO is synchronous and must run off the EDT. The selection logic is factored into the
 * pure [pick] so it is unit-testable without a filesystem.
 */
object SessionTitleReader {

    @Serializable
    private data class TitleLine(
        val type: String? = null,
        val aiTitle: String? = null,
        val customTitle: String? = null,
    )

    private val JSON = Json { ignoreUnknownKeys = true }

    /** Returns the binary's title for [sessionId], or null when there is no sidecar and nothing to name it. */
    fun read(sessionId: String): SessionTitle? =
        SessionStore.readLines(sessionId)?.let { pick(it) }

    /** Just the display text of [read] — for the callers that only paint it. */
    fun readTitle(sessionId: String): String? = read(sessionId)?.text

    /**
     * Picks the session title from raw JSONL lines, in order of authority: the last non-blank `customTitle`
     * (the user's own `/rename`), then the last `ai-title` line, then **the first thing the user actually
     * asked**. Blank/corrupt lines are skipped, never throw. Pure — unit-testable.
     *
     * **Why the first prompt is in here at all.** The binary does not write an `ai-title` line for a `--print`
     * session on its own: not one exists across the sessions on this machine. Without a fallback a chat stayed
     * "Chat 3" for its whole life, which is as useful as no title. The binary itself falls back the same way
     * for display (`getFirstMeaningfulUserMessageTextContent`), and the first prompt is a genuinely good name
     * for a conversation: it is what you went there to do.
     *
     * **It is a fallback and not the answer**, which is what [SessionTitle.authored] is for: a title generated
     * by the model is one control request away (`generate_session_title`), and the caller asks for one exactly
     * when nothing here authored a name. Once the binary persists that title, rule 2 wins on its own and this
     * never applies again.
     *
     * An authored title is returned verbatim: a `/rename` is the user's own words, and the binary's own
     * generated titles are already short. Only the fallback is cut to tab size ([asTitle]).
     *
     * **This function is the order of authority and nothing else.** Each recognition it rests on is a named
     * function below — [titleLineOf], [customTitleOf], [aiTitleOf], [usablePromptOf] — because the priorities
     * are what has to be readable at a glance here, while the rules deciding whether a given line carries a
     * name are each an argument of their own. There is deliberately no second function that also decides a
     * title: the live tab, the restored tab and "Open Previous Session…" all come through here, and two
     * deciders is how they start disagreeing about what a chat is called.
     */
    fun pick(lines: List<String>): SessionTitle? {
        var custom: String? = null
        var ai: String? = null
        var prompt: String? = null
        for (line in lines) {
            val parsed = titleLineOf(line) ?: continue
            customTitleOf(parsed)?.let { custom = it }
            aiTitleOf(parsed)?.let { ai = it }
            if (prompt == null) prompt = usablePromptOf(parsed, line)
        }
        val authored = custom ?: ai
        val text = authored ?: prompt?.let { asTitle(it) } ?: return null
        return SessionTitle(text = text, authored = authored != null, prompt = prompt)
    }

    /**
     * One raw JSONL line as a title record, or null when there is nothing readable in it.
     *
     * Blank and unparseable lines are the ordinary case rather than the exception: the file is appended to
     * while the binary runs, so a read can land on a half-written line, and the session file carries record
     * kinds this reader does not model at all. Neither may throw — a title is cosmetic, and a session whose
     * tab cannot be named must still open.
     */
    private fun titleLineOf(line: String): TitleLine? {
        if (line.isBlank()) return null
        return runCatching { JSON.decodeFromString<TitleLine>(line) }.getOrNull()
    }

    /**
     * The user's own name for the chat — what a `/rename` wrote — or null when this record carries none.
     *
     * Recognised by the FIELD and not by a record kind, because the line carrying it declares no `type` this
     * reader can key on. Blank is not a name: accepting one would replace a perfectly good `ai-title` or first
     * prompt with an empty tab, when the point of the order of authority is that each rule falls through to
     * the next.
     */
    private fun customTitleOf(parsed: TitleLine): String? = parsed.customTitle?.takeIf { it.isNotBlank() }

    /**
     * The name the model generated, or null when this record carries none.
     *
     * Recognised by the record TYPE, and that asymmetry with [customTitleOf] is the binary's spelling rather
     * than a choice made here: `ai-title` is a kind of record, so an `aiTitle` field riding on some other kind
     * of line is not a name anybody gave this chat. Blank is refused for the same reason as above.
     */
    private fun aiTitleOf(parsed: TitleLine): String? =
        if (parsed.type == "ai-title") parsed.aiTitle?.takeIf { it.isNotBlank() } else null

    /** Just the display text of [pick] — kept because most callers only paint it. */
    fun pickTitle(lines: List<String>): String? = pick(lines)?.text

    /**
     * Any text as a tab-sized title: its first non-blank line, cut on a word boundary so it never ends
     * mid-syllable. Null when there is nothing to show.
     *
     * The ONE shape rule, applied both to the first-prompt fallback and to whatever the model generates —
     * a title's length is not the model's to decide, and two truncations would drift apart.
     */
    fun asTitle(text: String): String? {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        if (firstLine.length <= MAX_TITLE) return firstLine
        val cut = firstLine.take(MAX_TITLE)
        return cut.substringBeforeLast(' ', cut).trimEnd(',', '.', ';', ':') + "…"
    }

    /**
     * The user's own words from [line], whole, or null when that line is not the user speaking.
     *
     * **Two gates, and they answer different questions.** [parsed] decides the record KIND — anything that is
     * not a `user` line cannot be a prompt — while the raw [line] is what the transcript parser needs, because
     * [SessionTranscriptReader.parseEntries] is the ONE parser for this format and a second one here is
     * exactly how 4.0.4 ended up rendering thinking twice.
     *
     * That parser is also what makes the answer usable, through `SyntheticUserText`: the binary writes its own
     * bookkeeping on `user` lines too — `<task-notification>` blocks, caveats, tool results — and titling a
     * chat "Caveat: The messages below were generated…" is worse than "Chat 3".
     *
     * Bounded by [MAX_PROMPT] because this text also travels to the binary as the description a generated
     * title is made from: a pasted stack trace is a first prompt too, and none of it past the opening
     * sentences tells anyone what the conversation is.
     */
    private fun usablePromptOf(parsed: TitleLine, line: String): String? {
        if (parsed.type != "user") return null
        val entries = runCatching { SessionTranscriptReader.parseEntries(listOf(line)) }.getOrNull().orEmpty()
        val text = entries.firstOrNull { it.speaker == "USER" }?.text?.trim().orEmpty()
        if (text.isBlank()) return null
        return text.take(MAX_PROMPT)
    }

    /** Long enough to tell two conversations apart, short enough that the tab is not all ellipsis. */
    private const val MAX_TITLE = 48

    /** Ceiling on the prompt handed to the binary as a title description; a title needs the opening, not the file. */
    private const val MAX_PROMPT = 2000
}
