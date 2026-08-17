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
 * **Three verdicts, not two, because "ends on a finished turn" and "has ever finished a turn" are different
 * questions.** A transcript that grows past its own ending is a resumption, and it is the plugin's only evidence
 * of one: the agent is alive again, writing records after a turn it had already closed. Answering that with the
 * same word as a transcript cut off mid-turn paints a running agent as dead, and answering a cut-off transcript
 * with the same word as a resumption paints a dead agent as running. Each verdict maps to one liveness, so they
 * stay apart.
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
     * The other shape a finished turn comes in: an assistant record carrying the agent's answer and **no
     * `stop_reason` at all**.
     *
     * Measured over the 566 agent transcripts on one developer machine: 41 of them end this way, on a real
     * model, with a full final answer in the content and nothing after it — and [endsTurn] called every one of
     * them cut off, which paints a finished agent RED and asserts that it failed.
     *
     * Two exclusions, and both are what keeps this from being a looser rule rather than a second one:
     *
     *  - **Only as the LAST record.** An assistant record with no tool call is ordinary mid-run narration, so
     *    admitting it into [endsTurn] would make almost every transcript "resumed" — 100 of those 566, i.e. a
     *    hundred dead agents painted as live. The resumption scan stays strictly on `end_turn`; this answers
     *    one question only, about the end of the file.
     *  - **Never a `<synthetic>` record.** That model name is the BINARY speaking, not the agent: every
     *    synthetic ending in that corpus is "You've hit your session limit", which is a transcript cut off
     *    mid-work and must keep reading as one.
     *
     * A `tool_use` block in the content means the turn is waiting on a result, whatever the `stop_reason` says.
     */
    private fun JsonObject.isFinalAnswer(): Boolean {
        if ((this["type"] as? JsonPrimitive)?.contentOrNull != "assistant") return false
        val message = this["message"] as? JsonObject ?: return false
        if ((message["stop_reason"] as? JsonPrimitive)?.contentOrNull != null) return false
        if ((message["model"] as? JsonPrimitive)?.contentOrNull == SYNTHETIC_MODEL) return false
        val content = message["content"] as? JsonArray ?: return false
        return content.none { (it as? JsonObject)?.get("type").let { t -> t as? JsonPrimitive }?.contentOrNull == "tool_use" }
    }

    /** The `model` the binary stamps on a record it wrote itself, rather than one the agent produced. */
    private const val SYNTHETIC_MODEL = "<synthetic>"
}
