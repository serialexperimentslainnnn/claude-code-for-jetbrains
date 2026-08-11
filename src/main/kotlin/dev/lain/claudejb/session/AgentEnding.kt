package dev.lain.claudejb.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * How an agent's own transcript ENDS — the only evidence there is about an agent from a previous run.
 *
 * **Why this exists.** A settled status is remembered per process (`task_notification` → `statusByToolUse`),
 * so after a restart the plugin knows nothing about the agents it is restoring. Treating them all as "cut
 * off" painted every agent of every past session RED, which does not just look wrong — it **asserts that they
 * failed**, and most of them had finished perfectly. Painting them all green would be the same lie in the
 * other direction.
 *
 * The binary already wrote the answer. The last record of `agent-<id>.jsonl` is either the assistant's final
 * message, with `stop_reason: end_turn` — the agent said its piece and stopped — or something else entirely:
 * `tool_use` (it was waiting on a tool that never came back), or a `user` line delivering a result it never
 * answered. Verified against real transcripts on this machine before being relied on.
 *
 * Pure: takes lines, returns a status. A malformed or absent line is not an ending.
 */
internal object AgentEnding {

    private val JSON = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * [AgentStatus.COMPLETED] when the transcript ends on a finished assistant turn, [AgentStatus.STOPPED]
     * when it stops mid-flight, and null when there is nothing to judge (no transcript yet).
     */
    fun of(lines: List<String>): AgentStatus? {
        val last = lines.lastOrNull { it.isNotBlank() } ?: return null
        val record = runCatching { JSON.parseToJsonElement(last).jsonObject }.getOrNull() ?: return null
        val message = record["message"]?.jsonObject
        val stop = message?.get("stop_reason")?.jsonPrimitive?.contentOrNull
        // `end_turn` is the model finishing. Anything else — `tool_use`, `max_tokens`, a user line — means the
        // agent was still mid-turn when the process it belonged to went away.
        return if (stop == "end_turn") AgentStatus.COMPLETED else AgentStatus.STOPPED
    }
}
