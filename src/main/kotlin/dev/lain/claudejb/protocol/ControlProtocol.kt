package dev.lain.claudejb.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * Builders for every line this plugin writes to the binary's stdin. Each returns a compact, single-line
 * JSON string (one NDJSON record). Shapes are built explicitly so the wire format matches the protocol
 * exactly, independent of [ClaudeJson] (de)serialization defaults.
 */
object ControlProtocol {

    fun newRequestId(): String = "req_" + UUID.randomUUID().toString().replace("-", "").take(16)

    /** stdin user message — sends a prompt (or a slash command, which is just user content starting with '/'). */
    fun userMessage(content: String, parentToolUseId: String? = null): String =
        buildJsonObject {
            put("type", "user")
            putJsonObject("message") {
                put("role", "user")
                put("content", content)
            }
            // put(key, String?) writes JsonNull when null, matching the protocol's explicit "parent_tool_use_id": null.
            put("parent_tool_use_id", parentToolUseId)
        }.toString()

    /** Generic host -> binary control_request envelope. */
    fun controlRequest(requestId: String, request: JsonObject): String =
        buildJsonObject {
            put("type", "control_request")
            put("request_id", requestId)
            put("request", request)
        }.toString()

    fun interruptRequest(requestId: String): String =
        controlRequest(requestId, buildJsonObject { put("subtype", "interrupt") })

    fun setModelRequest(requestId: String, model: String?): String =
        controlRequest(requestId, buildJsonObject {
            put("subtype", "set_model")
            if (model != null) put("model", model)
        })

    fun setPermissionModeRequest(requestId: String, mode: String): String =
        controlRequest(requestId, buildJsonObject {
            put("subtype", "set_permission_mode")
            put("mode", mode)
        })

    /** Optional handshake that returns rich SlashCommand + ModelInfo metadata for the UI. */
    fun initializeRequest(requestId: String): String =
        controlRequest(requestId, buildJsonObject { put("subtype", "initialize") })

    fun getContextUsageRequest(requestId: String): String =
        controlRequest(requestId, buildJsonObject { put("subtype", "get_context_usage") })

    fun getSessionCostRequest(requestId: String): String =
        controlRequest(requestId, buildJsonObject { put("subtype", "get_session_cost") })

    fun mcpStatusRequest(requestId: String): String =
        controlRequest(requestId, buildJsonObject { put("subtype", "mcp_status") })

    /** Sets the extended-thinking token budget at runtime (null disables thinking). */
    fun setMaxThinkingTokensRequest(requestId: String, maxThinkingTokens: Int?): String =
        controlRequest(requestId, buildJsonObject {
            put("subtype", "set_max_thinking_tokens")
            put("max_thinking_tokens", maxThinkingTokens)
        })

    fun reloadPluginsRequest(requestId: String): String =
        controlRequest(requestId, buildJsonObject { put("subtype", "reload_plugins") })

    // --- control_response: host's reply to a binary -> host control_request (e.g. can_use_tool) ---

    private fun controlResponse(payload: JsonObject): String =
        buildJsonObject {
            put("type", "control_response")
            put("response", payload)
        }.toString()

    /** Generic success reply carrying an optional response body. */
    fun success(requestId: String, response: JsonObject? = null): String =
        controlResponse(buildJsonObject {
            put("subtype", "success")
            put("request_id", requestId)
            if (response != null) put("response", response)
        })

    fun error(requestId: String, message: String): String =
        controlResponse(buildJsonObject {
            put("subtype", "error")
            put("request_id", requestId)
            put("error", message)
        })

    /**
     * PermissionResult allow. [updatedInput] is the input the binary will use to execute the tool: the
     * original forwarded unchanged if the user did not edit anything, or the diff-edited version.
     * The binary's runtime schema REQUIRES it (the published .d.ts marks it optional, but it is not):
     * omitting it causes the binary to reject the response and the tool to fail.
     */
    fun permissionAllow(requestId: String, updatedInput: JsonObject): String =
        success(requestId, buildJsonObject {
            put("behavior", "allow")
            put("updatedInput", updatedInput)
        })

    /** PermissionResult deny. */
    fun permissionDeny(requestId: String, message: String, interrupt: Boolean = false): String =
        success(requestId, buildJsonObject {
            put("behavior", "deny")
            put("message", message)
            put("interrupt", interrupt)
        })
}
