package dev.lain.claudejb.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Host-side decision engine for **hook callbacks** the `claude` binary invokes over the control channel.
 *
 * When launched with `--hooks` (or hooks declared in settings) and `includeHookEvents`, the binary delivers a
 * `control_request{subtype:"hook_callback", callback_id, input, tool_use_id?}` whenever a hook event fires. `input`
 * is one of the SDK `HookInput` shapes, discriminated by `hook_event_name` (PreToolUse, PostToolUse, Notification,
 * UserPromptSubmit, SessionStart/End, Stop, PreCompact/PostCompact, PermissionRequest, FileChanged, …). The host must
 * reply with a `control_response{subtype:"success", response:<HookJSONOutput>}`; the binary blocks on that reply.
 *
 * This broker is deliberately **pure and IDE-decoupled** so it can be unit-tested: it parses the generic JSON into a
 * [HookContext], runs the registered [HookHandler] for the event (default handlers below), turns the resulting
 * [HookDecision] into the exact `HookJSONOutput` wire shape via [buildResponse], and exposes any IDE work as data
 * ([HookSideEffect]). The caller ([ClaudeSession]) applies the side effects on the EDT and writes the control_response.
 *
 * Wire shapes are pinned against `node_modules/@anthropic-ai/claude-agent-sdk/sdk.d.ts`
 * (`SyncHookJSONOutput`, `PreToolUseHookSpecificOutput`, `PermissionRequestHookSpecificOutput`, etc.).
 */
class HookBroker(
    handlers: Map<String, HookHandler> = defaultHandlers(),
) {
    private val registry: MutableMap<String, HookHandler> = handlers.toMutableMap()

    /** Override or add a handler for [hookEventName] (e.g. "PreToolUse"). */
    fun register(hookEventName: String, handler: HookHandler) {
        registry[hookEventName] = handler
    }

    /** The single entry point: decide on a parsed [HookContext] using the registered handler (default Continue). */
    fun decide(ctx: HookContext): HookDecision =
        (registry[ctx.hookEventName] ?: HookHandler.PASS).handle(ctx)

    /**
     * Parses the inner `request` of a `hook_callback` control_request into a [HookContext].
     * `request` carries `callback_id`, `input` (the HookInput), and optional top-level `tool_use_id`.
     * Returns null when the frame is malformed (no `input`/`hook_event_name`) so the caller can reply with an error.
     */
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

    /**
     * Maps a [HookDecision] to the `HookJSONOutput` body the host returns inside `control_response.response`.
     *
     * Per `SyncHookJSONOutput`:
     *  - [HookDecision.Continue] → `{"continue":true}` (proceed; `SyncHookJSONOutput.continue` is optional, so this
     *    is equivalent to `{}` but explicit).
     *  - [HookDecision.Block]    → for PreToolUse: `{"hookSpecificOutput":{hookEventName,"permissionDecision":"deny",
     *                              "permissionDecisionReason":reason}}`; for PermissionRequest:
     *                              `{"hookSpecificOutput":{hookEventName,"decision":{"behavior":"deny","message":reason}}}`;
     *                              otherwise the generic `{"decision":"block","reason":reason}`.
     *  - [HookDecision.Modify]   → for PreToolUse: `{"hookSpecificOutput":{hookEventName,"permissionDecision":"allow",
     *                              "updatedInput":…}}`; for PermissionRequest the `decision.behavior:"allow"` variant.
     *  - [HookDecision.Annotate] → `{"systemMessage":msg}` (plus `hookSpecificOutput.additionalContext` for the events
     *                              whose specific output carries it, so the text reaches the model, not just the UI).
     *
     * [callbackId] is echoed back as `callback_id` so the binary can correlate (defensive: the SDK also keys by the
     * control_response `request_id`, which the caller sets separately).
     */
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
                        // Generic events cannot rewrite input; degrade to continue.
                        put("continue", true)
                    }
                }
                is HookDecision.Annotate -> {
                    put("systemMessage", decision.systemMessage)
                    // The events whose specific output carries additionalContext get it there too, so the
                    // text is injected into the model's context (systemMessage alone is host-display only).
                    if (hookEventName in ANNOTATABLE_EVENTS) {
                        putJsonObject("hookSpecificOutput") {
                            put("hookEventName", hookEventName)
                            put("additionalContext", decision.systemMessage)
                        }
                    }
                }
            }
        }

    /**
     * Derives the IDE-side effects for a context+decision. The broker never touches the IDE itself; the caller applies
     * these on the EDT. Side effects are independent of the wire decision (e.g. a Notification still continues but the
     * IDE should show a balloon; a FileChanged still continues but the VFS should refresh).
     */
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
        // A non-continue decision is itself worth surfacing in the transcript.
        when (decision) {
            is HookDecision.Block -> effects += HookSideEffect.TranscriptNote("Hook blocked: ${decision.reason}")
            is HookDecision.Annotate -> effects += HookSideEffect.TranscriptNote(decision.systemMessage)
            else -> {}
        }
        return effects
    }

    companion object {
        /** Events whose `*HookSpecificOutput` exposes `additionalContext` (so an annotation reaches the model). */
        private val ANNOTATABLE_EVENTS = setOf(
            "PreToolUse", "PostToolUse", "PostToolUseFailure", "PostToolBatch",
            "UserPromptSubmit", "SessionStart", "Notification",
        )

        /**
         * Default registry: IDE-useful but **non-intrusive**. Lifecycle/observability events (Notification, FileChanged,
         * SessionStart/End, Stop, PreCompact/PostCompact) Continue on the wire — their value is the [HookSideEffect].
         * The gating events (PreToolUse, PermissionRequest) Continue too: hook-level blocking is opt-in user policy,
         * and the real permission gate is still `can_use_tool`. Replace any entry via [register] to add user rules.
         */
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

/** Parsed, IDE-agnostic view of a single `hook_callback` request. Unknown fields stay in [raw]. */
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

/** What the host wants the binary to do with this hook. */
sealed interface HookDecision {
    /** Proceed (no objection). Maps to `{"continue":true}`. */
    data object Continue : HookDecision

    /** Veto: deny the tool/permission (PreToolUse/PermissionRequest) or block the turn (others). */
    data class Block(val reason: String) : HookDecision

    /** Rewrite the tool input before execution (PreToolUse / PermissionRequest allow path). */
    data class Modify(val updatedInput: JsonObject) : HookDecision

    /** Inject extra context / a system message without blocking. */
    data class Annotate(val systemMessage: String) : HookDecision
}

/** A per-event handler. Pure: given a [HookContext] it returns a [HookDecision]. */
fun interface HookHandler {
    fun handle(ctx: HookContext): HookDecision

    companion object {
        /** The do-nothing handler: always [HookDecision.Continue]. */
        val PASS: HookHandler = HookHandler { HookDecision.Continue }
    }
}

/** IDE work the broker wants done, as data, so [ClaudeSession] applies it on the EDT. */
sealed interface HookSideEffect {
    /** Show an IDE notification balloon. */
    data class NotifyUser(val message: String, val title: String? = null) : HookSideEffect

    /** Refresh the VFS for a path the binary reported changed (`event` is change/add/unlink). */
    data class RefreshFile(val path: String, val event: String? = null) : HookSideEffect

    /** Lifecycle marker (SessionStart/End, Stop) — for logging / tab state. [detail] is source/reason if any. */
    data class Marker(val event: String, val detail: String? = null) : HookSideEffect

    /** A line to append to the transcript (compaction status, hook block reason, annotation). */
    data class TranscriptNote(val text: String) : HookSideEffect
}

// --- local JSON helper (the protocol-package `str` is internal to that package) ---

/** String value of [key], or null if absent or not a primitive. Accepts unquoted primitives (numbers/bools) too. */
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
