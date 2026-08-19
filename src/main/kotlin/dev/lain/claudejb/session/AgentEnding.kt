package dev.lain.claudejb.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * What an agent's own transcript says about whether it is over — the only evidence there is about an agent the
 * plugin did not watch run.
 *
 * A settled status is remembered per process (`task_notification` → `statusByToolUse`), so a restored agent
 * carries nothing. The binary wrote the answer instead: the records of `agent-<id>.jsonl`. A finished assistant
 * turn is `stop_reason: end_turn`; anything else at the end — `tool_use` waiting on a tool that never came back,
 * `max_tokens`, a `user` line delivering a result nobody answered — is a turn that never closed.
 *
 * **Four verdicts, because "over" and "still going" are each two different things.**
 *  - "ends on a finished turn" and "has ever finished a turn" are different questions. A transcript that grows
 *    past its own ending is a resumption, and it is the plugin's only evidence of one: the agent is alive again,
 *    writing records after a turn it had already closed. Answering that with the same word as a transcript cut
 *    off mid-turn paints a running agent as dead, and the reverse paints a dead agent as running.
 *  - "the turn never closed" and "the work was STOPPED" are different too, and that one is the bug this file was
 *    last fixed for. An agent that was cancelled, or that the binary cut off, leaves a transcript with no closed
 *    turn at the end — indistinguishable, under the first three verdicts, from one still working. So it read as
 *    working, for ever: nothing was ever going to be appended that could change the verdict, and a [RESUMED] one
 *    is answered RUNNING unconditionally, so re-reading the file a thousand times gave the same wrong answer.
 *    Measured over the 672 agent transcripts on one developer machine, **155 of them end on one of the two
 *    markers below** — 41 read as [RESUMED] and 114 as [UNFINISHED], i.e. every one of them shown as live work
 *    that had in fact stopped, some of it days earlier.
 *
 * Each verdict maps to one liveness, which is what keeps those cases apart.
 *
 * Pure: takes lines, returns a verdict. A blank line is not a record and a malformed one is not an ending.
 */
internal object AgentEnding {

    /** What an agent's own transcript says about whether it is over. */
    enum class Ending {
        /** The last record is a finished assistant turn (`stop_reason: end_turn`). */
        COMPLETED,

        /** A finished turn, and more records after it: the agent was resumed. */
        RESUMED,

        /**
         * The work STOPPED before it was finished, and the last record says so — see [isAbortMarker].
         *
         * Distinct from [UNFINISHED], which is a transcript that merely *stops*: this one carries the binary's
         * own statement that nothing more is coming. That difference is the whole reason the verdict exists,
         * because an agent's colour is decided by whether anything could still be written.
         */
        ABORTED,

        /** No finished turn at the end, and none before it either: cut off mid-turn. */
        UNFINISHED,
    }

    private val JSON = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** `null` when there is nothing to judge (no transcript yet, or nothing parseable). */
    fun of(lines: List<String>): Ending? {
        val records = lines.mapNotNull { line ->
            if (line.isBlank()) null else runCatching { JSON.parseToJsonElement(line).jsonObject }.getOrNull()
        }
        if (records.isEmpty()) return null
        // FIRST, and on the last record only: a marker outranks every rule below it, including [RESUMED]. An
        // agent that closed a turn, was resumed, and was then cancelled has both a finished turn behind it and
        // an ending — and only the ending is about now.
        if (records.last().isAbortMarker()) return Ending.ABORTED
        val lastFinished = records.indexOfLast { it.endsTurn() }
        return when {
            lastFinished == records.lastIndex -> Ending.COMPLETED
            records.last().isFinalAnswer() -> Ending.COMPLETED
            lastFinished >= 0 -> Ending.RESUMED
            else -> Ending.UNFINISHED
        }
    }

    /** Safe casts, not `jsonObject`/`jsonPrimitive`: a record whose `message` or `stop_reason` has the wrong
     *  shape is malformed, and malformed is not an ending. */
    private fun JsonObject.endsTurn(): Boolean {
        val message = this["message"] as? JsonObject ?: return false
        return (message["stop_reason"] as? JsonPrimitive)?.contentOrNull == "end_turn"
    }

    /**
     * The two records the binary writes when work stops without finishing — the ONLY two, measured rather than
     * guessed, over every `agent-*.jsonl` on a developer machine (672 files; 77 and 78 of them end here).
     *
     *  - **the user cancelled it**: a `user` record whose text is `[Request interrupted by user]`, or its
     *    `…by user for tool use]` variant — hence a prefix, not an equality;
     *  - **the binary cut it off**: an `assistant` record under the reserved model name `<synthetic>`, which is
     *    the binary speaking in the agent's file rather than the agent. Every one in that corpus is a session
     *    limit ("You've hit your session limit · resets 3:30am"), and the STRUCTURED field is what is matched:
     *    that prose carries a clock and a locale, so keying on it would be a rule that expires at midnight.
     *
     * **Only as the LAST record**, like [isFinalAnswer] and for the same reason: an interruption in the middle
     * of a transcript is something the agent recovered from and kept working past, so admitting it anywhere
     * would kill agents that are demonstrably alive. Measured on the same corpus, no marker has ever been
     * written directly after a closed turn — every one follows work in flight — so there is no case where this
     * buries an ending the agent had already reached.
     */
    private fun JsonObject.isAbortMarker(): Boolean = isUserInterrupt() || isBinaryCutOff()

    private fun JsonObject.isUserInterrupt(): Boolean =
        (this["type"] as? JsonPrimitive)?.contentOrNull == "user" &&
            text().trimStart().startsWith(INTERRUPT_PREFIX)

    private fun JsonObject.isBinaryCutOff(): Boolean {
        if ((this["type"] as? JsonPrimitive)?.contentOrNull != "assistant") return false
        val message = this["message"] as? JsonObject ?: return false
        return (message["model"] as? JsonPrimitive)?.contentOrNull == SYNTHETIC_MODEL
    }

    /** A record's leading text: its first `text` block, or plain-string content. `""` when it carries neither. */
    private fun JsonObject.text(): String {
        val content = (this["message"] as? JsonObject)?.get("content") ?: return ""
        (content as? JsonPrimitive)?.contentOrNull?.let { return it }
        val blocks = content as? JsonArray ?: return ""
        return blocks.asSequence()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
            ?.let { (it["text"] as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
    }

    /**
     * The other shape a finished turn comes in: an assistant record carrying the agent's answer and **no
     * `stop_reason` at all**.
     *
     * Measured over the same corpus: 41 transcripts end this way, on a real model, with a full final answer in
     * the content and nothing after it — and [endsTurn] called every one of them cut off, which paints a
     * finished agent RED and asserts that it failed.
     *
     * **Only as the LAST record.** An assistant record with no tool call is ordinary mid-run narration, so
     * admitting it into [endsTurn] would make almost every transcript "resumed" — 100 of those transcripts,
     * i.e. a hundred dead agents painted as live. The resumption scan stays strictly on `end_turn`; this
     * answers one question only, about the end of the file.
     *
     * A `<synthetic>` record cannot reach this: [of] answers [Ending.ABORTED] before it asks, since that model
     * name is the binary cutting the agent off and never the agent finishing.
     *
     * A `tool_use` block in the content means the turn is waiting on a result, whatever the `stop_reason` says.
     */
    private fun JsonObject.isFinalAnswer(): Boolean {
        if ((this["type"] as? JsonPrimitive)?.contentOrNull != "assistant") return false
        val message = this["message"] as? JsonObject ?: return false
        if ((message["stop_reason"] as? JsonPrimitive)?.contentOrNull != null) return false
        val content = message["content"] as? JsonArray ?: return false
        return content.none { (it as? JsonObject)?.get("type").let { t -> t as? JsonPrimitive }?.contentOrNull == "tool_use" }
    }

    /** The `model` the binary stamps on a record it wrote itself, rather than one the agent produced. */
    private const val SYNTHETIC_MODEL = "<synthetic>"

    /** How a cancellation reads in the agent's own file; the `…for tool use]` variant shares the prefix. */
    private const val INTERRUPT_PREFIX = "[Request interrupted"
}
