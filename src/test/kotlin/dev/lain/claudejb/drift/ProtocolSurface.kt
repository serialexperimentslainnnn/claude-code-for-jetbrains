package dev.lain.claudejb.drift

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ProtocolSurface(
    val eventTypes: Set<String> = emptySet(),
    val subtypes: Set<String> = emptySet(),
    val unionMembers: Set<String> = emptySet(),
) {
    companion object {
        private val SUBTYPE = Regex("""subtype:\s*['"]([^'"]+)['"]""")
        private val UNION = Regex("""type\s+(?:SDKMessage|StdoutMessage)\s*=\s*([^;]+);""")
        private val LENIENT = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        fun fromDts(dts: String): ProtocolSurface {
            val subtypes = SUBTYPE.findAll(dts).map { it.groupValues[1] }.toSet()
            val members = UNION.findAll(dts)
                .flatMap { m -> m.groupValues[1].split('|') }
                .map { it.trim().substringAfterLast('.') }
                .filter { it.isNotEmpty() }
                .toSet()
            return ProtocolSurface(subtypes = subtypes, unionMembers = members)
        }

        fun fromCapture(ndjson: String): ProtocolSurface {
            val types = LinkedHashSet<String>()
            val subtypes = LinkedHashSet<String>()
            for (raw in ndjson.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val obj = runCatching { LENIENT.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: continue
                (obj["type"] as? JsonPrimitive)?.let { types.add(it.content) }
                val sub = (obj["request"] as? JsonObject)?.subtype()
                    ?: (obj["response"] as? JsonObject)?.subtype()
                    ?: (obj["subtype"] as? JsonPrimitive)?.content
                if (sub != null) subtypes.add(sub)
            }
            return ProtocolSurface(eventTypes = types, subtypes = subtypes)
        }

        private fun JsonObject.subtype(): String? = (this["subtype"] as? JsonPrimitive)?.content

        val KNOWN_EVENT_TYPES: Set<String> = setOf(
            "system", "assistant", "user", "stream_event", "result",
            "control_request", "control_response", "rate_limit_event", "keep_alive",
            "auth_status", "tool_progress", "tool_use_summary", "prompt_suggestion",
        )

        val KNOWN_SUBTYPES: Set<String> = setOf(
            "init", "local_command_output", "status", "compact_boundary",
            "task_started", "task_progress", "task_updated", "task_notification",
            "thinking_tokens", "notification", "permission_denied", "session_state_changed",
            "api_retry", "commands_changed", "memory_recall", "files_persisted",
            "plugin_install", "hook_started", "hook_progress", "hook_response", "mirror_error",
            "model_refusal_fallback", "informational", "model_refusal_no_fallback", "worker_shutting_down",
            "background_tasks_changed", "control_request_progress",
            "elicitation_complete",
            "can_use_tool", "hook_callback", "request_user_dialog", "elicitation",
            "success", "error", "error_during_execution",
            "initialize", "interrupt", "set_model", "set_permission_mode", "set_max_thinking_tokens",
            "set_color", "rename_session", "get_context_usage", "get_session_cost", "get_binary_version",
            "get_settings", "mcp_status", "mcp_call", "mcp_message", "mcp_set_servers", "mcp_reconnect",
            "mcp_toggle", "read_file", "rewind_files", "seed_read_state", "stop_task", "background_tasks",
            "cancel_async_message", "file_suggestions", "reload_plugins", "apply_flag_settings",
            "get_usage", "register_repo_root", "reload_skills",
            "list_models", "get_plan", "get_workspace_diff",
            "generate_session_title", "side_question",
        )
    }
}

data class SurfaceDelta(val added: Set<String>, val removed: Set<String>) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty()
}

fun surfaceDelta(old: Set<String>, new: Set<String>): SurfaceDelta =
    SurfaceDelta(added = new - old, removed = old - new)
