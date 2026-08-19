package dev.lain.claudejb.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class CanUseToolRequest(
    @SerialName("tool_name") val toolName: String = "",
    val input: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
    val title: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val description: String? = null,
    @SerialName("tool_use_id") val toolUseId: String = "",
    @SerialName("blocked_path") val blockedPath: String? = null,
    @SerialName("decision_reason") val decisionReason: String? = null,
)

@Serializable
data class AskQuestion(
    val question: String = "",
    val header: String = "",
    val options: List<AskOption> = emptyList(),
    val multiSelect: Boolean = false,
)

@Serializable
data class AskOption(
    val label: String = "",
    val description: String = "",
    val preview: String? = null,
)

fun parseAskQuestions(input: kotlinx.serialization.json.JsonObject): List<AskQuestion> {
    val arr = input["questions"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return runCatching {
        arr.map { ClaudeJson.decodeFromJsonElement(AskQuestion.serializer(), it) }
    }.getOrDefault(emptyList())
}

@Serializable
data class ElicitationRequest(
    @SerialName("mcp_server_name") val mcpServerName: String = "",
    val message: String = "",
    val mode: String? = null,
    val url: String? = null,
    @SerialName("elicitation_id") val elicitationId: String? = null,
    @SerialName("requested_schema") val requestedSchema: JsonObject? = null,
    val title: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val description: String? = null,
)

data class ElicitField(
    val name: String,
    val type: String,
    val title: String?,
    val required: Boolean,
)

private val PRIMITIVE_ELICIT_TYPES = setOf("string", "number", "integer", "boolean")

fun parseElicitationFields(schema: JsonObject?): List<ElicitField> {
    schema ?: return emptyList()
    return runCatching {
        val props = schema["properties"] as? JsonObject ?: return emptyList()
        val required = (schema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toSet().orEmpty()
        val fields = ArrayList<ElicitField>(props.size)
        for ((name, spec) in props) {
            val obj = spec as? JsonObject ?: return emptyList()
            val type = obj.str("type") ?: return emptyList()
            if (type !in PRIMITIVE_ELICIT_TYPES) return emptyList()
            fields += ElicitField(name, type, obj.str("title"), name in required)
        }
        fields
    }.getOrDefault(emptyList())
}
