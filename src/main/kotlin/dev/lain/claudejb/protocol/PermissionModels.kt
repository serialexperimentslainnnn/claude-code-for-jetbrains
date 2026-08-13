package dev.lain.claudejb.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// ---------------------------------------------------------------------------
// Control protocol: can_use_tool request (binary -> host) and its data.
// ---------------------------------------------------------------------------

/** Inner payload of a `can_use_tool` control_request. The hook that drives native diff review. */
@Serializable
data class CanUseToolRequest(
    @SerialName("tool_name") val toolName: String = "",
    val input: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
    val title: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val description: String? = null,
    @SerialName("tool_use_id") val toolUseId: String = "",
    @SerialName("blocked_path") val blockedPath: String? = null,
    /** Explains why this permission request was triggered (e.g. a deny rule, an out-of-root path). */
    @SerialName("decision_reason") val decisionReason: String? = null,
)

// ---------------------------------------------------------------------------
// AskUserQuestion: a built-in tool delivered as a can_use_tool whose input carries
// the questions. The host renders them and replies allow with updatedInput = input +
// {"answers": {questionText: chosenLabel}} (multi-select labels are comma-joined).
// Verified empirically against claude 2.1.150 (the result echoes the chosen option).
// ---------------------------------------------------------------------------

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

/** Parses the `questions` array out of an AskUserQuestion tool input; empty if malformed. */
fun parseAskQuestions(input: kotlinx.serialization.json.JsonObject): List<AskQuestion> {
    val arr = input["questions"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return runCatching {
        arr.map { ClaudeJson.decodeFromJsonElement(AskQuestion.serializer(), it) }
    }.getOrDefault(emptyList())
}

// ---------------------------------------------------------------------------
// elicitation control request (binary -> host): an MCP server asks the user for
// input. URL mode points at a link to complete (e.g. an OAuth flow); form mode
// carries a JSON-schema `requested_schema` whose primitive properties become input
// fields. Answered with ElicitResult {action: accept|decline|cancel, content?}.
// Verified against SDKControlElicitationRequest in sdk.d.ts.
// ---------------------------------------------------------------------------

@Serializable
data class ElicitationRequest(
    @SerialName("mcp_server_name") val mcpServerName: String = "",
    val message: String = "",
    val mode: String? = null, // "form" | "url" | null
    val url: String? = null,
    @SerialName("elicitation_id") val elicitationId: String? = null,
    @SerialName("requested_schema") val requestedSchema: JsonObject? = null,
    val title: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val description: String? = null,
)

/** One primitive input field extracted from an elicitation's requested_schema. */
data class ElicitField(
    val name: String,
    val type: String, // string | number | integer | boolean
    val title: String?,
    val required: Boolean,
)

private val PRIMITIVE_ELICIT_TYPES = setOf("string", "number", "integer", "boolean")

/**
 * Extracts the flat primitive fields of an elicitation `requested_schema` (a JSON-schema object): one
 * [ElicitField] per `properties` entry whose `type` is string/number/integer/boolean. Returns an empty list
 * when the schema is absent, malformed, or carries any nested/object property — the caller then falls back to
 * a plain Accept-with-no-content card. Never throws.
 */
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
