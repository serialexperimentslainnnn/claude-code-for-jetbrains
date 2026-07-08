package dev.lain.claudejb.drift

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A minimal, *regular* slice of the Claude Agent SDK / binary protocol surface that can be extracted
 * reliably from two sources and diffed across versions:
 *
 *  - [fromDts] — the SDK's TypeScript declaration (`sdk.d.ts`): the **static** contract. New protocol
 *    kinds appear as a new `subtype: 'x'` literal or a new member of the top-level `SDKMessage` /
 *    `StdoutMessage` unions. Parsing TS generically is brittle, but these two shapes are dead regular.
 *  - [fromCapture] — a live NDJSON capture of what the (auto-updating) `claude` binary actually emits:
 *    the **runtime** contract. Each line's top-level `type` and (for control frames) `subtype` is read.
 *
 * Drift = the difference between what the latest source exposes and what the plugin already knows
 * ([KNOWN_EVENT_TYPES] / [KNOWN_SUBTYPES], mirrored from `protocol/ClaudeEvent.kt`) or referenced
 * (the previous `sdk.d.ts`). The extraction is pure and offline so it can be unit-tested with fixtures;
 * the live download + probe happens in [DriftLiveCheck], driven by the `checkDrift` Gradle task.
 */
data class ProtocolSurface(
    /** Top-level message `type` discriminators (meaningful for a runtime capture). */
    val eventTypes: Set<String> = emptySet(),
    /** `subtype` discriminators — system subtypes + control request/response kinds. */
    val subtypes: Set<String> = emptySet(),
    /** Members of the `SDKMessage` / `StdoutMessage` unions (meaningful for the `.d.ts`). */
    val unionMembers: Set<String> = emptySet(),
) {
    companion object {
        // Quote-tolerant: the SDK uses single quotes today, but don't let a future double-quote break us.
        private val SUBTYPE = Regex("""subtype:\s*['"]([^'"]+)['"]""")
        private val UNION = Regex("""type\s+(?:SDKMessage|StdoutMessage)\s*=\s*([^;]+);""")
        private val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Extracts the protocol surface from a `sdk.d.ts` body: every `subtype` string literal, and every
         * member of the two top-level message unions (normalized to the bare type name, dropping a
         * `coreTypes.` qualifier). Event types aren't pulled from TS (every nested object has a `type:`,
         * so it's noisy) — the runtime capture is the authoritative source for top-level types.
         */
        fun fromDts(dts: String): ProtocolSurface {
            val subtypes = SUBTYPE.findAll(dts).map { it.groupValues[1] }.toSet()
            val members = UNION.findAll(dts)
                .flatMap { m -> m.groupValues[1].split('|') }
                .map { it.trim().substringAfterLast('.') }
                .filter { it.isNotEmpty() }
                .toSet()
            return ProtocolSurface(subtypes = subtypes, unionMembers = members)
        }

        /**
         * Extracts the surface from an NDJSON capture of the binary's stdout: the top-level `type` of each
         * line, plus the `subtype` of control frames (read from the nested `request`/`response` object, or
         * the top level for `system`/`result`). Malformed lines are skipped, mirroring the lenient codec.
         */
        fun fromCapture(ndjson: String): ProtocolSurface {
            val types = LinkedHashSet<String>()
            val subtypes = LinkedHashSet<String>()
            for (raw in ndjson.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val obj = runCatching { LENIENT.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: continue
                (obj["type"] as? JsonPrimitive)?.let { types.add(it.content) }
                // Control frames carry the subtype inside request/response; system/result carry it at top level.
                val sub = (obj["request"] as? JsonObject)?.subtype()
                    ?: (obj["response"] as? JsonObject)?.subtype()
                    ?: (obj["subtype"] as? JsonPrimitive)?.content
                if (sub != null) subtypes.add(sub)
            }
            return ProtocolSurface(eventTypes = types, subtypes = subtypes)
        }

        private fun JsonObject.subtype(): String? = (this["subtype"] as? JsonPrimitive)?.content

        /**
         * Top-level message `type`s the plugin's [dev.lain.claudejb.protocol] parser handles explicitly.
         * **Keep in sync with `protocol/ClaudeEvent.kt`'s `when (type)`** — a capture type outside this set
         * falls into the parser's `else -> Other` branch (it's tolerated, but unmodeled = actionable drift).
         */
        val KNOWN_EVENT_TYPES: Set<String> = setOf(
            "system", "assistant", "user", "stream_event", "result",
            "control_request", "control_response", "rate_limit_event", "keep_alive",
            "auth_status", "tool_progress", "tool_use_summary", "prompt_suggestion",
        )

        /**
         * The **full triaged** subtype surface: every `subtype` literal the plugin is aware of — whether it
         * *parses* it (system subtypes), *handles* it as a binary->host control request, *sends* it as a
         * host->binary control request, or knowingly leaves it as `Other`/`UnsupportedControlRequest`. A
         * `subtype` outside this set is genuinely new (worth a typed model or an explicit decision). Keep in
         * sync with `protocol/ClaudeEvent.kt` (receive) and `protocol/ControlProtocol.kt` (send).
         */
        val KNOWN_SUBTYPES: Set<String> = setOf(
            // system subtypes the parser maps to a typed event
            "init", "local_command_output", "status", "compact_boundary",
            "task_started", "task_progress", "task_updated", "task_notification",
            "thinking_tokens", "notification", "permission_denied", "session_state_changed",
            "api_retry", "commands_changed", "memory_recall", "files_persisted",
            "plugin_install", "hook_started", "hook_progress", "hook_response", "mirror_error",
            "model_refusal_fallback", "informational", "model_refusal_no_fallback", "worker_shutting_down",
            "background_tasks_changed", "control_request_progress",
            // system subtype we receive but knowingly leave as Other (URL-elicitation confirmation)
            "elicitation_complete",
            // control requests (binary -> host) we answer
            "can_use_tool", "hook_callback", "request_user_dialog", "elicitation",
            // control response envelopes + the terminal error result subtype
            "success", "error", "error_during_execution",
            // control requests (host -> binary) the plugin sends or knowingly triages (out of UI scope)
            "initialize", "interrupt", "set_model", "set_permission_mode", "set_max_thinking_tokens",
            "set_color", "rename_session", "get_context_usage", "get_session_cost", "get_binary_version",
            "get_settings", "mcp_status", "mcp_call", "mcp_message", "mcp_set_servers", "mcp_reconnect",
            "mcp_toggle", "read_file", "rewind_files", "seed_read_state", "stop_task", "background_tasks",
            "cancel_async_message", "file_suggestions", "reload_plugins", "apply_flag_settings",
            "get_usage", "register_repo_root", "reload_skills",
            // thin-client control requests we knowingly don't send: models come from the `initialize` reply, and
            // the plan / workspace-diff dialogs are the remote thin client's, not ours.
            "list_models", "get_plan", "get_workspace_diff",
        )
    }
}

/** Added/removed members between two surfaces along one axis. */
data class SurfaceDelta(val added: Set<String>, val removed: Set<String>) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty()
}

/** [new] minus [old] (added) and [old] minus [new] (removed) — order-independent set diff. */
fun surfaceDelta(old: Set<String>, new: Set<String>): SurfaceDelta =
    SurfaceDelta(added = new - old, removed = old - new)
