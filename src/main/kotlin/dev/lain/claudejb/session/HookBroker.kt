package dev.lain.claudejb.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class HookBroker(
    handlers: Map<String, HookHandler> = defaultHandlers(),
) {
    private val registry: MutableMap<String, HookHandler> = handlers.toMutableMap()

    fun register(hookEventName: String, handler: HookHandler) {
        registry[hookEventName] = handler
    }

    fun decide(ctx: HookContext): HookDecision =
        (registry[ctx.hookEventName] ?: HookHandler.PASS).handle(ctx)

    fun parse(request: JsonObject): HookContext? {
        val input = request["input"] as? JsonObject ?: return null
        val hookEventName = input.str("hook_event_name") ?: return null
        val callbackId = request.str("callback_id").orEmpty()
        val toolUseId = request.str("tool_use_id") ?: input.str("tool_use_id")
        return HookContext(
            callbackId = callbackId,
            hookEventName = hookEventName,
            toolUseId = toolUseId,
            toolName = input.str("tool_name"),
            toolInput = input["tool_input"] as? JsonObject,
            sessionId = input.str("session_id"),
            cwd = input.str("cwd"),
            message = input.str("message"),
            title = input.str("title"),
            filePath = input.str("file_path"),
            fileEvent = input.str("event"),
            source = input.str("source"),
            trigger = input.str("trigger"),
            reason = input.str("reason"),
            raw = input,
        )
    }

    fun buildResponse(callbackId: String, decision: HookDecision, hookEventName: String): JsonObject =
        buildJsonObject {
            if (callbackId.isNotEmpty()) put("callback_id", callbackId)
            when (decision) {
                is HookDecision.Continue -> {
                    put("continue", true)
                }

                is HookDecision.Block -> when (hookEventName) {
                    "PreToolUse" -> putJsonObject("hookSpecificOutput") {
                        put("hookEventName", "PreToolUse")
                        put("permissionDecision", "deny")
                        put("permissionDecisionReason", decision.reason)
                    }

                    "PermissionRequest" -> putJsonObject("hookSpecificOutput") {
                        put("hookEventName", "PermissionRequest")
                        putJsonObject("decision") {
                            put("behavior", "deny")
                            put("message", decision.reason)
                        }
                    }

                    else -> {
                        put("decision", "block")
                        put("reason", decision.reason)
                    }
                }

                is HookDecision.Modify -> when (hookEventName) {
                    "PreToolUse" -> putJsonObject("hookSpecificOutput") {
                        put("hookEventName", "PreToolUse")
                        put("permissionDecision", "allow")
                        put("updatedInput", decision.updatedInput)
                    }

                    "PermissionRequest" -> putJsonObject("hookSpecificOutput") {
                        put("hookEventName", "PermissionRequest")
                        putJsonObject("decision") {
                            put("behavior", "allow")
                            put("updatedInput", decision.updatedInput)
                        }
                    }

                    else -> {
                        put("continue", true)
                    }
                }

                is HookDecision.Annotate -> {
                    put("systemMessage", decision.systemMessage)
                    if (hookEventName in ANNOTATABLE_EVENTS) {
                        putJsonObject("hookSpecificOutput") {
                            put("hookEventName", hookEventName)
                            put("additionalContext", decision.systemMessage)
                        }
                    }
                }
            }
        }

    fun sideEffects(ctx: HookContext, decision: HookDecision): List<HookSideEffect> {
        val effects = mutableListOf<HookSideEffect>()
        when (ctx.hookEventName) {
            "Notification" -> ctx.message?.takeIf { it.isNotBlank() }?.let {
                effects += HookSideEffect.NotifyUser(it, ctx.title)
            }

            "FileChanged" -> ctx.filePath?.takeIf { it.isNotBlank() }?.let {
                effects += HookSideEffect.RefreshFile(it, ctx.fileEvent)
            }

            "SessionStart", "SessionEnd", "Stop" ->
                effects += HookSideEffect.Marker(ctx.hookEventName, ctx.source ?: ctx.reason)

            "PreCompact" ->
                effects += HookSideEffect.TranscriptNote(
                    "Compacting conversation" + (ctx.trigger?.let { " ($it)" } ?: "") + "…",
                )

            "PostCompact" ->
                effects += HookSideEffect.TranscriptNote("Conversation compacted.")
        }
        when (decision) {
            is HookDecision.Block -> effects += HookSideEffect.TranscriptNote("Hook blocked: ${decision.reason}")
            is HookDecision.Annotate -> effects += HookSideEffect.TranscriptNote(decision.systemMessage)
            else -> {}
        }
        return effects
    }

    companion object {
        private val ANNOTATABLE_EVENTS = setOf(
            "PreToolUse",
            "PostToolUse",
            "PostToolUseFailure",
            "PostToolBatch",
            "UserPromptSubmit",
            "SessionStart",
            "Notification",
        )

        fun defaultHandlers(): Map<String, HookHandler> = mapOf(
            "PreToolUse" to HookHandler.PASS,
            "PostToolUse" to HookHandler.PASS,
            "PermissionRequest" to HookHandler.PASS,
            "Notification" to HookHandler.PASS,
            "UserPromptSubmit" to HookHandler.PASS,
            "SessionStart" to HookHandler.PASS,
            "SessionEnd" to HookHandler.PASS,
            "Stop" to HookHandler.PASS,
            "PreCompact" to HookHandler.PASS,
            "PostCompact" to HookHandler.PASS,
            "FileChanged" to HookHandler.PASS,
        )
    }
}

data class HookContext(
    val callbackId: String,
    val hookEventName: String,
    val toolUseId: String? = null,
    val toolName: String? = null,
    val toolInput: JsonObject? = null,
    val sessionId: String? = null,
    val cwd: String? = null,
    val message: String? = null,
    val title: String? = null,
    val filePath: String? = null,
    val fileEvent: String? = null,
    val source: String? = null,
    val trigger: String? = null,
    val reason: String? = null,
    val raw: JsonObject? = null,
)

sealed interface HookDecision {
    data object Continue : HookDecision

    data class Block(val reason: String) : HookDecision

    data class Modify(val updatedInput: JsonObject) : HookDecision

    data class Annotate(val systemMessage: String) : HookDecision
}

fun interface HookHandler {
    fun handle(ctx: HookContext): HookDecision

    companion object {
        val PASS: HookHandler = HookHandler { HookDecision.Continue }
    }
}

sealed interface HookSideEffect {
    data class NotifyUser(val message: String, val title: String? = null) : HookSideEffect

    data class RefreshFile(val path: String, val event: String? = null) : HookSideEffect

    data class Marker(val event: String, val detail: String? = null) : HookSideEffect

    data class TranscriptNote(val text: String) : HookSideEffect
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
