package dev.lain.claudejb.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

object ControlProtocol {

    private const val REQUEST_ID_HEX_CHARS = 16

    fun newRequestId(): String =
        "req_" + UUID.randomUUID().toString().replace("-", "").take(REQUEST_ID_HEX_CHARS)

    fun userMessage(content: String, parentToolUseId: String? = null, uuid: String? = null): String =
        buildJsonObject {
            put("type", "user")
            putJsonObject("message") {
                put("role", "user")
                put("content", content)
            }
            put("parent_tool_use_id", parentToolUseId)
            if (uuid != null) put("uuid", uuid)
        }.toString()

    fun userMessageWithImages(
        content: String,
        images: List<Pair<String, String>>,
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

    fun controlRequest(requestId: String, request: JsonObject): String =
        buildJsonObject {
            put("type", "control_request")
            put("request_id", requestId)
            put("request", request)
        }.toString()

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

    fun initializeRequest(requestId: String): String =
        controlRequest(requestId, buildJsonObject { put("subtype", "initialize") })

    fun renameSessionRequest(requestId: String, title: String): String =
        controlRequest(
            requestId,
            buildJsonObject {
                put("subtype", "rename_session")
                put("title", title)
            },
        )

    fun seedReadStateRequest(requestId: String, path: String, mtime: Long): String =
        controlRequest(
            requestId,
            buildJsonObject {
                put("subtype", "seed_read_state")
                put("path", path)
                put("mtime", mtime)
            },
        )

    fun stopTaskRequest(requestId: String, taskId: String): String =
        controlRequest(
            requestId,
            buildJsonObject {
                put("subtype", "stop_task")
                put("task_id", taskId)
            },
        )

    fun mcpReconnectRequest(requestId: String, serverName: String): String =
        controlRequest(
            requestId,
            buildJsonObject {
                put("subtype", "mcp_reconnect")
                put("serverName", serverName)
            },
        )

    fun mcpToggleRequest(requestId: String, serverName: String, enabled: Boolean): String =
        controlRequest(
            requestId,
            buildJsonObject {
                put("subtype", "mcp_toggle")
                put("serverName", serverName)
                put("enabled", enabled)
            },
        )

    private fun controlResponse(payload: JsonObject): String =
        buildJsonObject {
            put("type", "control_response")
            put("response", payload)
        }.toString()

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

    fun permissionAllow(requestId: String, updatedInput: JsonObject): String =
        success(
            requestId,
            buildJsonObject {
                put("behavior", "allow")
                put("updatedInput", updatedInput)
            },
        )

    fun permissionDeny(requestId: String, message: String, interrupt: Boolean = false): String =
        success(
            requestId,
            buildJsonObject {
                put("behavior", "deny")
                put("message", message)
                put("interrupt", interrupt)
            },
        )

    fun userDialogCancelled(requestId: String): String =
        success(requestId, buildJsonObject { put("behavior", "cancelled") })

    fun elicitationResult(requestId: String, action: String, content: JsonObject? = null): String =
        success(
            requestId,
            buildJsonObject {
                put("action", action)
                if (content != null) put("content", content)
            },
        )
}
