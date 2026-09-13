package dev.lain.claudejb.model.protocol.parse

import dev.lain.claudejb.model.protocol.ClaudeEvent
import dev.lain.claudejb.model.protocol.ClaudeJson
import dev.lain.claudejb.model.protocol.models.CanUseToolRequest
import dev.lain.claudejb.model.protocol.models.ElicitationRequest
import dev.lain.claudejb.model.protocol.models.RateLimitInfo
import dev.lain.claudejb.model.protocol.str
import kotlinx.serialization.json.JsonObject

internal object ControlParsers {

    private val CONTROL_DECODERS: Map<String, (String, JsonObject) -> ClaudeEvent> = buildMap {
        put("can_use_tool") { id, request ->
            runCatching {
                ClaudeEvent.PermissionRequest(id, ClaudeJson.decodeFromJsonElement(CanUseToolRequest.serializer(), request))
            }.getOrDefault(ClaudeEvent.UnsupportedControlRequest(id, "can_use_tool"))
        }
        put("hook_callback") { id, request -> ClaudeEvent.HookCallback(id, request) }
        put("request_user_dialog") { id, request ->
            ClaudeEvent.UserDialogRequest(
                id,
                request.str("dialog_kind"),
                (request["payload"] as? JsonObject) ?: JsonObject(emptyMap()),
                request.str("tool_use_id"),
            )
        }
        put("elicitation") { id, request ->
            runCatching {
                ClaudeEvent.Elicitation(id, ClaudeJson.decodeFromJsonElement(ElicitationRequest.serializer(), request))
            }.getOrDefault(ClaudeEvent.UnsupportedControlRequest(id, "elicitation"))
        }
    }

    fun parseControlRequest(root: JsonObject): List<ClaudeEvent> {
        val requestId = root.str("request_id") ?: return emptyList()
        val request = root["request"] as? JsonObject ?: return emptyList()
        val subtype = request.str("subtype")
        val decoder = CONTROL_DECODERS[subtype] ?: return listOf(ClaudeEvent.UnsupportedControlRequest(requestId, subtype))
        return listOf(decoder(requestId, request))
    }

    fun parseControlResponse(root: JsonObject): List<ClaudeEvent> {
        val response = root["response"] as? JsonObject ?: return emptyList()
        val requestId = response.str("request_id") ?: return emptyList()
        return listOf(
            ClaudeEvent.ControlResult(
                requestId = requestId,
                success = response.str("subtype") == "success",
                payload = response["response"] as? JsonObject,
                error = response.str("error"),
            ),
        )
    }

    fun parseControlCancel(root: JsonObject): List<ClaudeEvent> {
        val requestId = root.str("request_id") ?: return emptyList()
        return listOf(ClaudeEvent.ControlCancel(requestId))
    }

    fun parseRateLimit(root: JsonObject): List<ClaudeEvent> {
        val info = root["rate_limit_info"] as? JsonObject ?: return emptyList()
        return runCatching {
            listOf(ClaudeEvent.RateLimit(ClaudeJson.decodeFromJsonElement(RateLimitInfo.serializer(), info)))
        }.getOrDefault(emptyList())
    }
}
