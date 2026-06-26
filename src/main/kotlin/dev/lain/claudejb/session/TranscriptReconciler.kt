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
 * **Threading:** every method assumes it is already on the EDT (the session marshals protocol events there
 * before calling in). It does not marshal threads itself — mirroring the previous inline behaviour.
 *
 * The reconciler does **not** own the [transcript]; it is injected so the session keeps a single shared model.
 */
class TranscriptReconciler(private val transcript: TranscriptModel) {

    // The assistant text/thinking entry currently being grown by deltas (null when no live block is open).
    private var liveAssistant: TranscriptEntry? = null
    private var liveThinking: TranscriptEntry? = null
    // The thinking entry of the CURRENT message, kept even after a text delta closed the live thinking block, so the
    // finalized `AssistantThinking` block can REPLACE it instead of appending a duplicate at the end (which left the
    // "Thought process" fold out of order, after the answer). Reset on every message boundary.
    private var settledThinking: TranscriptEntry? = null

    /** Appends a top-level assistant text delta, starting a new entry if none is live. Ends any live thinking. */
    fun appendAssistant(delta: String) {
        liveThinking = null // close the growing thinking block, but keep settledThinking for finalize-replace
        val entry = liveAssistant
        if (entry == null) liveAssistant = transcript.add(Speaker.ASSISTANT, delta)
        else transcript.append(entry, delta)
    }

    /** Replaces the live assistant entry with its finalized text (or adds one), then closes the block. */
    fun finalizeAssistant(full: String) {
        val entry = liveAssistant
        if (entry != null) transcript.replaceText(entry, full) else transcript.add(Speaker.ASSISTANT, full)
        liveAssistant = null
    }

    /** Appends a top-level thinking delta, starting a new entry if none is live. */
    fun appendThinking(delta: String) {
        val entry = liveThinking
        if (entry == null) {
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
     */
    fun finalizeThinking(full: String) {
        val entry = liveThinking ?: settledThinking
        if (entry != null) transcript.replaceText(entry, full) else transcript.add(Speaker.THINKING, full)
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

    /**
     * Anchors a subagent's finalized assistant text under its Agent's tool_use id without touching the top-level
     * live stream. Subagent text arrives finalized with a parent id; top-level text keeps the streaming path.
     */
    fun addSubagentText(text: String, parentToolUseId: String) {
        transcript.add(Speaker.ASSISTANT, text, parentToolUseId = parentToolUseId)
    }
}
