package dev.lain.claudejb.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * Builders for every line this plugin writes to the binary's stdin. Each returns a compact, single-line
 * JSON string (one NDJSON record). Shapes are built explicitly so the wire format matches the protocol
 * exactly, independent of [ClaudeJson] (de)serialization defaults.
 */
object ControlProtocol {

    /**
     * Hex characters kept from a random UUID for a request id. 16 hex chars = 64 bits, which only has to be
     * unique among the handful of control requests in flight on ONE session at ONE moment — this is a
     * correlation key, not a security token.
     */
    private const val REQUEST_ID_HEX_CHARS = 16

    fun newRequestId(): String =
        "req_" + UUID.randomUUID().toString().replace("-", "").take(REQUEST_ID_HEX_CHARS)

    /** stdin user message — sends a prompt (or a slash command, which is just user content starting with '/'). */
    fun userMessage(content: String, parentToolUseId: String? = null, uuid: String? = null): String =
        buildJsonObject {
            put("type", "user")
            putJsonObject("message") {
                put("role", "user")
                put("content", content)
            }
            // put(key, String?) writes JsonNull when null, matching the protocol's explicit "parent_tool_use_id": null.
            put("parent_tool_use_id", parentToolUseId)
            // Client-supplied message id so we can later `rewind_files` to this turn's checkpoint.
            if (uuid != null) put("uuid", uuid)
        }.toString()

    /**
     * stdin user message with image attachments: a multi-block `content` array of `{type:"text"}` (omitted when the
     * prompt is blank) followed by one `{type:"image",source:{type:"base64",media_type,data}}` block per image, matching
     * the Anthropic content-block shape the binary forwards to the model. Falls back to the plain string form when there
     * are no images, so the common path is unchanged.
     */
    fun userMessageWithImages(
        content: String,
        images: List<Pair<String, String>>, // (mediaType, base64)
        parentToolUseId: String? = null,
        uuid: String? = null,
    ): String {
        if (images.isEmpty()) return userMessage(content, parentToolUseId, uuid)
        return buildJsonObject {
            put("type", "user")
            putJsonObject("message") {
                put("role", "user")
                putJsonArray("content") {
                    if (content.isNotBlank()) {
                        addJsonObject {
                            put("type", "text")
                            put("text", content)
                        }
                    }
                    for ((mediaType, base64) in images) {
                        addJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                put("media_type", mediaType)
                                put("data", base64)
                            }
                        }
                    }
                }
            }
            put("parent_tool_use_id", parentToolUseId)
            if (uuid != null) put("uuid", uuid)
        }.toString()
    }

    /** Generic host -> binary control_request envelope. */
    fun controlRequest(requestId: String, request: JsonObject): String =
        buildJsonObject {
            put("type", "control_request")
            put("request_id", requestId)
            put("request", request)
        }.toString()

    /**
     * A control request from its subtype and whatever fields it carries — the generic form of the named
     * builders below.
     *
     * This is the shape a request declared in a catalogue takes (`Asks`, sent through `SessionQueries.ask`):
     * one line there and no hand-written builder here. A named builder below earns its place only when
     * something sends that request directly, outside the catalogue.
     */
    fun of(requestId: String, subtype: String, params: JsonObjectBuilder.() -> Unit = {}): String =
        controlRequest(
            requestId,
            buildJsonObject {
                put("subtype", subtype)
                params()
            },
        )

    fun interruptRequest(requestId: String): String =
        controlRequest(requestId, buildJsonObject { put("subtype", "interrupt") })

    fun setModelRequest(requestId: String, model: String?): String =
        controlRequest(
            requestId,
            buildJsonObject {
                put("subtype", "set_model")
                if (model != null) put("model", model)
            },
        )

    fun setPermissionModeRequest(requestId: String, mode: String): String =
        controlRequest(
            requestId,
            buildJsonObject {
                put("subtype", "set_permission_mode")
                put("mode", mode)
            },
        )

    /** Optional handshake that returns rich SlashCommand + ModelInfo metadata for the UI. */
    fun initializeRequest(requestId: String): String =
        controlRequest(requestId, buildJsonObject { put("subtype", "initialize") })

    /** Sets the user-facing title for the current session. */
    fun renameSessionRequest(requestId: String, title: String): String =
        controlRequest(
            requestId,
            buildJsonObject {
                put("subtype", "rename_session")
                put("title", title)
            },
        )

    /** Seeds the readFileState cache with a path+mtime entry so Edit validation passes after the Read was dropped. */
    fun seedReadStateRequest(requestId: String, path: String, mtime: Long): String =
        controlRequest(
            requestId,
            buildJsonObject {
                put("subtype", "seed_read_state")
                put("path", path)
                put("mtime", mtime)
            },
        )

    /** Stops a running task. */
    fun stopTaskRequest(requestId: String, taskId: String): String =
        controlRequest(
            requestId,
            buildJsonObject {
                put("subtype", "stop_task")
                put("task_id", taskId)
            },
        )

    /** Reconnects a disconnected or failed MCP server. NB: wire field is camelCase `serverName`. */
    fun mcpReconnectRequest(requestId: String, serverName: String): String =
        controlRequest(
            requestId,
            buildJsonObject {
                put("subtype", "mcp_reconnect")
                put("serverName", serverName)
            },
        )

    /** Enables or disables an MCP server. NB: wire field is camelCase `serverName`. */
    fun mcpToggleRequest(requestId: String, serverName: String, enabled: Boolean): String =
        controlRequest(
            requestId,
            buildJsonObject {
                put("subtype", "mcp_toggle")
                put("serverName", serverName)
                put("enabled", enabled)
            },
        )

    // --- control_response: host's reply to a binary -> host control_request (e.g. can_use_tool) ---

    private fun controlResponse(payload: JsonObject): String =
        buildJsonObject {
            put("type", "control_response")
            put("response", payload)
        }.toString()

    /** Generic success reply carrying an optional response body. */
    fun success(requestId: String, response: JsonObject? = null): String =
        controlResponse(
            buildJsonObject {
                put("subtype", "success")
                put("request_id", requestId)
                if (response != null) put("response", response)
            },
        )

    fun error(requestId: String, message: String): String =
        controlResponse(
            buildJsonObject {
                put("subtype", "error")
                put("request_id", requestId)
                put("error", message)
            },
        )

    /**
     * PermissionResult allow. [updatedInput] is the input the binary will use to execute the tool: the
     * original forwarded unchanged if the user did not edit anything, or the diff-edited version.
     * The binary's runtime schema REQUIRES it (the published .d.ts marks it optional, but it is not):
     * omitting it causes the binary to reject the response and the tool to fail.
     */
    fun permissionAllow(requestId: String, updatedInput: JsonObject): String =
        success(
            requestId,
            buildJsonObject {
                put("behavior", "allow")
                put("updatedInput", updatedInput)
            },
        )

    /** PermissionResult deny. */
    fun permissionDeny(requestId: String, message: String, interrupt: Boolean = false): String =
        success(
            requestId,
            buildJsonObject {
                put("behavior", "deny")
                put("message", message)
                put("interrupt", interrupt)
            },
        )

    /**
     * request_user_dialog reply. The host implements no custom dialog kinds, so it cancels — the CLI then
     * applies the dialog's own default. (UserDialogResult = {behavior:"cancelled"}.)
     */
    fun userDialogCancelled(requestId: String): String =
        success(requestId, buildJsonObject { put("behavior", "cancelled") })

    /** elicitation reply (ElicitResult). [action] ∈ accept|decline|cancel; [content] is only meaningful for accept. */
    fun elicitationResult(requestId: String, action: String, content: JsonObject? = null): String =
        success(
            requestId,
            buildJsonObject {
                put("action", action)
                if (content != null) put("content", content)
            },
        )
}
