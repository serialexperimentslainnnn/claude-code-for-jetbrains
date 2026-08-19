package dev.lain.claudejb.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AgentMeta(
    val agentId: String,
    val agentType: String? = null,
    val description: String? = null,
    val toolUseId: String? = null,
    val parentAgentId: String? = null,
    val spawnDepth: Int = 1,
) {
    fun label(): String =
        description?.takeIf { it.isNotBlank() }
            ?: agentType?.takeIf { it.isNotBlank() }
            ?: agentId

    companion object {
        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        const val FILE_PREFIX = "agent-"
        const val META_SUFFIX = ".meta.json"
        const val TRANSCRIPT_SUFFIX = ".jsonl"

        fun parse(agentId: String, body: String): AgentMeta? {
            val obj = runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            return AgentMeta(
                agentId = agentId,
                agentType = obj.str("agentType"),
                description = obj.str("description"),
                toolUseId = obj.str("toolUseId"),
                parentAgentId = obj.str("parentAgentId"),
                spawnDepth = (obj["spawnDepth"]?.jsonPrimitive?.intOrNull ?: 1).coerceAtLeast(1),
            )
        }

        fun agentIdOfMetaFile(fileName: String): String? =
            fileName.takeIf { it.startsWith(FILE_PREFIX) && it.endsWith(META_SUFFIX) }
                ?.removeSuffix(META_SUFFIX)
                ?.removePrefix(FILE_PREFIX)

        fun transcriptFile(agentId: String): String = "$FILE_PREFIX$agentId$TRANSCRIPT_SUFFIX"

        fun bareAgentId(raw: String): String = raw.removePrefix(FILE_PREFIX)

        private fun JsonObject.str(key: String): String? =
            this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
