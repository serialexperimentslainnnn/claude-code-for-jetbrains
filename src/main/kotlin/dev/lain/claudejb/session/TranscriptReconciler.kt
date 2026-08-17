package dev.lain.claudejb.session

/**
 * Streaming reconciliation for a single session's top-level assistant output.
 *
 * Owns the two "live" entries that incoming deltas grow in place — the assistant text entry and the
 * thinking entry currently being streamed — and folds the binary's `text_delta` / `thinking_delta` /
 * finalized-block / message-boundary events into a [TranscriptModel] with the exact same semantics the
 * session used inline:
 *
 *  - a `text_delta` ends any in-flight thinking block and appends to (or starts) the live assistant entry;
 *  - a finalized assistant/thinking block replaces the live entry's text (or adds a fresh one) and closes it;
 *  - a message boundary (a new `message_start`, or a *top-level* tool call) resets both live pointers so the
 *    next delta starts a new entry instead of growing a finished paragraph.
 *
 * **Whose output this is:** every write takes the frame's `parent_tool_use_id` and drops the write when it
 * names a subagent — see [belongsHere]. The rule lives here rather than at the dispatch sites because this
 * class is what decides what a transcript contains: a new path added tomorrow calls one of these methods and
 * is filtered by construction, instead of having to remember an `if` that four call sites each spelled out.
 *
 * **Trimming:** the model is bounded and drops its OLDEST rows past [TranscriptModel.MAX_ENTRIES], which can
 * take an entry a live pointer here still points at. Appending to such an entry writes text nowhere the page
 * will ever see — no row, no error. So a pointer whose entry is [TranscriptEntry.trimmed] counts as **no live
 * block**: it is dropped and the next delta starts a fresh entry, which is the only outcome that keeps the
 * text on screen. Trimming is a memory bound and never data loss — the binary's own session file on disk
 * holds the whole conversation.
 *
 * **Threading:** every method assumes it is already on the EDT (the session marshals protocol events there
 * before calling in). It does not marshal threads itself — mirroring the previous inline behaviour.
 *
 * The reconciler does **not** own the [transcript]; it is injected so the session keeps a single shared model.
 */
class TranscriptReconciler(private val transcript: TranscriptModel) {

    companion object {
        /**
         * Whether output labelled with [parentToolUseId] belongs in THIS transcript.
         *
         * A subagent runs through the SAME stdout channel as the main conversation and is told apart by
         * nothing but this field. Its reasoning and its text belong to its own tab, which is reconstructed
         * from the binary's per-agent file (`<sessionId>/subagents/agent-<id>.jsonl`) by [AgentRegistry] —
         * so a subagent's blocks are **dropped here, not re-routed**: the row already exists somewhere else,
         * and writing a second copy into the main transcript is precisely the interleaving that made a
         * session running dozens of agents unreadable — consecutive "Thought process" rows belonging to
         * different agents, with no way to follow any single one.
         *
         * **This is the whole of the protection, and it only works on frames the binary actually labels.**
         * `assistant` / `user` frames carry the id (the binary stamps every progress event a tool yields with
         * the tool call's own id before forwarding it, so a subagent's blocks arrive labelled). A
         * `stream_event` frame does NOT: the binary emits every one of them with a hard-coded
         * `parent_tool_use_id: null`, and it never converts a subagent's partial messages into one — only its
         * assembled `assistant` / `user` messages are forwarded. So a delta reaching this class is a main-run
         * delta by construction, not by the check; if that ever stops being true the deltas would arrive
         * unlabelled and no filter written against this field could see them.
         */
        fun belongsHere(parentToolUseId: String?): Boolean = parentToolUseId == null
    }

    // The assistant text/thinking entry currently being grown by deltas (null when no live block is open).
    private var liveAssistant: TranscriptEntry? = null
    private var liveThinking: TranscriptEntry? = null

    // The thinking entry of the CURRENT message, kept even after a text delta closed the live thinking block, so the
    // finalized `AssistantThinking` block can REPLACE it instead of appending a duplicate at the end (which left the
    // "Thought process" fold out of order, after the answer). Reset on every message boundary.
    private var settledThinking: TranscriptEntry? = null

    /** A pointer only counts while its entry is still in the model: a trimmed entry is no live block. */
    private fun live(entry: TranscriptEntry?): TranscriptEntry? = entry?.takeUnless { it.trimmed }

    /**
     * Appends a top-level assistant text delta, starting a new entry if none is live. Ends any live thinking.
     *
     * A subagent's delta is dropped and — this is the part that is not obvious — it must also leave the live
     * pointers ALONE. Closing the main run's thinking block on someone else's delta would split one paragraph
     * of reasoning into two rows every time an agent spoke.
     */
    fun appendAssistant(delta: String, parentToolUseId: String? = null) {
        if (!belongsHere(parentToolUseId)) return
        liveThinking = null // close the growing thinking block, but keep settledThinking for finalize-replace
        val entry = live(liveAssistant)
        if (entry == null) {
            liveAssistant = transcript.add(Speaker.ASSISTANT, delta)
        } else {
            transcript.append(entry, delta)
        }
    }

    /** Replaces the live assistant entry with its finalized text (or adds one), then closes the block. */
    fun finalizeAssistant(full: String, parentToolUseId: String? = null) {
        if (!belongsHere(parentToolUseId)) return
        val entry = live(liveAssistant)
        if (entry != null) transcript.replaceText(entry, full) else transcript.add(Speaker.ASSISTANT, full)
        liveAssistant = null
    }

    /**
     * Appends a top-level thinking delta, starting a new entry if none is live.
     *
     * A blank delta never *opens* a block: with REDACTED thinking (Opus 4.8+) the model streams no reasoning text
     * at all, and creating an entry for it rendered an empty "Thought process" fold. Once a block is open a blank
     * delta is a harmless no-op append.
     */
    fun appendThinking(delta: String, parentToolUseId: String? = null) {
        if (!belongsHere(parentToolUseId)) return
        val entry = live(liveThinking)
        if (entry == null) {
            if (delta.isBlank()) return // nothing to show — don't open an empty fold
            liveThinking = transcript.add(Speaker.THINKING, delta)
            settledThinking = liveThinking
        } else {
            transcript.append(entry, delta)
        }
    }

    /**
     * Replaces the message's thinking entry with its finalized text, then closes the block. Uses [settledThinking]
     * (the entry the deltas built) even when a text delta already cleared [liveThinking] — otherwise the finalized
     * block would be appended as a SECOND, out-of-order "Thought process" after the answer.
     *
     * A subagent's finalized block is dropped **before** the pointers are read, and that ordering is the whole
     * point: reaching the tail of this function would clear `settledThinking`, so the main run's own finalized
     * block would then find nothing to replace and be appended as a duplicate row — the 4.0.4 duplicated
     * "Thought process" defect, re-entered through the one path that is supposed to prevent it.
     */
    fun finalizeThinking(full: String, parentToolUseId: String? = null) {
        if (!belongsHere(parentToolUseId)) return
        val entry = live(liveThinking) ?: live(settledThinking)
        when {
            // Redacted thinking (Opus 4.8+) finalizes as an EMPTY block. Never open a fold for it, and never blank
            // out a fold that already streamed real reasoning text — keep what the user was shown.
            full.isBlank() -> Unit

            entry != null -> transcript.replaceText(entry, full)

            else -> transcript.add(Speaker.THINKING, full)
        }
        liveThinking = null
        settledThinking = null
    }

    /**
     * Closes both live blocks so the next delta starts a fresh entry. Called on a new `message_start` and on a
     * top-level `tool_use` (a subagent's tool call must not cut a top-level paragraph, so the session only calls
     * this for top-level boundaries).
     */
    fun onMessageBoundary() {
        liveAssistant = null
        liveThinking = null
        settledThinking = null
    }

    // NB `addSubagentText` lived here until 5.5.0. A subagent's text no longer belongs in this transcript at
    // all: it goes to that agent's own tab, read from the binary's per-agent file by AgentRegistry. Deleted
    // rather than left warm — a helper that still anchors agent output under an Agent card is exactly how
    // the interleaving this release removes would come back.
}
