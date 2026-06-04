package dev.lain.claudejb.protocol

import kotlinx.serialization.json.JsonObject
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
    ): String {
        if (images.isEmpty()) return userMessage(content, parentToolUseId)
        return buildJsonObject {
            put("type", "user")
            putJsonObject("message") {
                put("role", "user")
                putJsonArray("content") {
                    if (content.isNotBlank()) addJsonObject {
                        put("type", "text")
                        put("text", content)
                    }
                    for ((mediaType, base64) in images) addJsonObject {
                        put("type", "image")
                        putJsonObject("source") {
                            put("type", "base64")
                            put("media_type", mediaType)
                            put("data", base64)
                        }
                    }
                }
            }
            put("parent_tool_use_id", parentToolUseId)
        }.toString()
    }

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

    fun reloadPluginsRequest(requestId: String): String =
        controlRequest(requestId, buildJsonObject { put("subtype", "reload_plugins") })

    /** Sets the user-facing title for the current session. */
    fun renameSessionRequest(requestId: String, title: String): String =
        controlRequest(requestId, buildJsonObject {
            put("subtype", "rename_session")
            put("title", title)
        })

    /** Sets the session accent color (an agent color name or "default" to reset). */
    fun setColorRequest(requestId: String, color: String): String =
        controlRequest(requestId, buildJsonObject {
            put("subtype", "set_color")
            put("color", color)
        })

    /** Returns the effective merged settings and the raw per-source settings. */
    fun getSettingsRequest(requestId: String): String =
        controlRequest(requestId, buildJsonObject { put("subtype", "get_settings") })

    /** Requests the responder's CLI binary version. */
    fun getBinaryVersionRequest(requestId: String): String =
        controlRequest(requestId, buildJsonObject { put("subtype", "get_binary_version") })

    /** Requests at-mention file autocomplete suggestions for a partial path prefix. */
    fun fileSuggestionsRequest(requestId: String, query: String): String =
        controlRequest(requestId, buildJsonObject {
            put("subtype", "file_suggestions")
            put("query", query)
        })

    /** Reads a file from the session filesystem (gated by the same read-permission rules as the Read tool). */
    fun readFileRequest(requestId: String, path: String, maxBytes: Int? = null, encoding: String? = null): String =
        controlRequest(requestId, buildJsonObject {
            put("subtype", "read_file")
            put("path", path)
            if (maxBytes != null) put("max_bytes", maxBytes)
            if (encoding != null) put("encoding", encoding)
        })

    /** Rewinds file changes made since a specific user message. */
    fun rewindFilesRequest(requestId: String, userMessageId: String, dryRun: Boolean? = null): String =
        controlRequest(requestId, buildJsonObject {
            put("subtype", "rewind_files")
            put("user_message_id", userMessageId)
            if (dryRun != null) put("dry_run", dryRun)
        })

    /** Seeds the readFileState cache with a path+mtime entry so Edit validation passes after the Read was dropped. */
    fun seedReadStateRequest(requestId: String, path: String, mtime: Long): String =
        controlRequest(requestId, buildJsonObject {
            put("subtype", "seed_read_state")
            put("path", path)
            put("mtime", mtime)
        })

    /** Stops a running task. */
    fun stopTaskRequest(requestId: String, taskId: String): String =
        controlRequest(requestId, buildJsonObject {
            put("subtype", "stop_task")
            put("task_id", taskId)
        })

    /** Backgrounds in-flight foreground tasks (a single tool_use's task when given, else all — Ctrl+B semantics). */
    fun backgroundTasksRequest(requestId: String, toolUseId: String? = null): String =
        controlRequest(requestId, buildJsonObject {
            put("subtype", "background_tasks")
            if (toolUseId != null) put("tool_use_id", toolUseId)
        })

    /** Reconnects a disconnected or failed MCP server. NB: wire field is camelCase `serverName`. */
    fun mcpReconnectRequest(requestId: String, serverName: String): String =
        controlRequest(requestId, buildJsonObject {
            put("subtype", "mcp_reconnect")
            put("serverName", serverName)
        })

    /** Enables or disables an MCP server. NB: wire field is camelCase `serverName`. */
    fun mcpToggleRequest(requestId: String, serverName: String, enabled: Boolean): String =
        controlRequest(requestId, buildJsonObject {
            put("subtype", "mcp_toggle")
            put("serverName", serverName)
            put("enabled", enabled)
        })

    /** Replaces the set of dynamically managed MCP servers (a `name -> server config` object). */
    fun mcpSetServersRequest(requestId: String, servers: JsonObject): String =
        controlRequest(requestId, buildJsonObject {
            put("subtype", "mcp_set_servers")
            put("servers", servers)
        })

    /** Invokes an MCP tool via the subprocess MCP client without a model turn. */
    fun mcpCallRequest(requestId: String, tool: String, arguments: JsonObject? = null): String =
        controlRequest(requestId, buildJsonObject {
            put("subtype", "mcp_call")
            put("tool", tool)
            if (arguments != null) put("arguments", arguments)
        })

    /** Applies a set of flag-derived settings to the session. */
    fun applyFlagSettingsRequest(requestId: String, settings: JsonObject): String =
        controlRequest(requestId, buildJsonObject {
            put("subtype", "apply_flag_settings")
            put("settings", settings)
        })

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
