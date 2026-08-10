package dev.lain.claudejb.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the binary itself records about one subagent, read from `subagents/agent-<id>.meta.json`.
 *
 * **This is the reason the agent tree is data rather than inference.** Verified against `claude` 2.1.226:
 * next to every `agent-<id>.jsonl` the binary writes a sidecar carrying
 * `{agentType, description, toolUseId, parentAgentId, spawnDepth}`. So the parent chain
 * ([parentAgentId]), the depth to indent at ([spawnDepth]), the tab's title ([description], the model's own
 * summary of the task) and the link back to the card that spawned it ([toolUseId]) all come from the binary.
 * Nothing here is reconstructed by joining events, which is what an earlier design would have had to do —
 * `system/task_started` carries no parent at all.
 *
 * Every field is optional on purpose: a sidecar from a newer binary must never fail to parse, and a missing
 * `description` costs a generic tab label, not a dropped agent.
 */
data class AgentMeta(
    /** `agent-<id>` — the id in the file name, and the identity used everywhere else. */
    val agentId: String,
    /** The registered agent type (`general-purpose`, a custom agent…), shown as the tab's tooltip. */
    val agentType: String? = null,
    /** The model-generated task summary; the tab's title. */
    val description: String? = null,
    /** The `tool_use_id` of the Task call that spawned it — the anchor of the transcript card. */
    val toolUseId: String? = null,
    /** The parent agent's id, or null for an agent spawned directly by the main turn. */
    val parentAgentId: String? = null,
    /** 1 for an agent of the main turn, 2 for an agent of that agent, and so on. */
    val spawnDepth: Int = 1,
) {
    /** What the tab shows: the model's own description, else the type, else the raw id. */
    fun label(): String =
        description?.takeIf { it.isNotBlank() }
            ?: agentType?.takeIf { it.isNotBlank() }
            ?: agentId

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** File-name prefix and `.jsonl`/`.meta.json` suffixes the binary uses inside `subagents/`. */
        const val FILE_PREFIX = "agent-"
        const val META_SUFFIX = ".meta.json"
        const val TRANSCRIPT_SUFFIX = ".jsonl"

        /**
         * Parses a `meta.json` body for [agentId]. Pure and tolerant — a corrupt or partial sidecar yields
         * null rather than throwing, and the caller simply does not admit that agent.
         */
        fun parse(agentId: String, body: String): AgentMeta? {
            val obj = runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            return AgentMeta(
                agentId = agentId,
                agentType = obj.str("agentType"),
                description = obj.str("description"),
                toolUseId = obj.str("toolUseId"),
                parentAgentId = obj.str("parentAgentId"),
                // Absent depth is treated as top level: better a flat row than a wrong indent.
                spawnDepth = (obj["spawnDepth"]?.jsonPrimitive?.intOrNull ?: 1).coerceAtLeast(1),
            )
        }

        /** `agent-abc.meta.json` → `agent-abc`; null for anything that is not one of the binary's sidecars. */
        fun agentIdOfMetaFile(fileName: String): String? =
            fileName.takeIf { it.startsWith(FILE_PREFIX) && it.endsWith(META_SUFFIX) }
                ?.removeSuffix(META_SUFFIX)

        private fun JsonObject.str(key: String): String? =
            this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
