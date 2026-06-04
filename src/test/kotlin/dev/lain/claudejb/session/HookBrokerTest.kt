package dev.lain.claudejb.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins [HookBroker]: parsing each HookInput variant, the default (non-intrusive) decisions, the exact `HookJSONOutput`
 * wire shape per event, and the data-only [HookSideEffect] surface. Pure JVM — no IDE runtime.
 */
class HookBrokerTest {

    private val broker = HookBroker()

    private fun callback(callbackId: String, toolUseId: String? = null, input: JsonObject): JsonObject =
        buildJsonObject {
            put("subtype", "hook_callback")
            put("callback_id", callbackId)
            if (toolUseId != null) put("tool_use_id", toolUseId)
            put("input", input)
        }

    private fun input(eventName: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        buildJsonObject {
            put("hook_event_name", eventName)
            put("session_id", "sess-1")
            put("cwd", "/proj")
            build()
        }

    private fun JsonObject.obj(key: String): JsonObject = this[key] as JsonObject
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    // --- parsing ---

    @Test
    fun `parses PreToolUse with tool name and input`() {
        val req = callback("cb1", toolUseId = "tu1", input = input("PreToolUse") {
            put("tool_name", "Bash")
            put("tool_input", buildJsonObject { put("command", "ls") })
        })
        val ctx = broker.parse(req)!!
        assertEquals("PreToolUse", ctx.hookEventName)
        assertEquals("cb1", ctx.callbackId)
        assertEquals("tu1", ctx.toolUseId)
        assertEquals("Bash", ctx.toolName)
        assertEquals("ls", ctx.toolInput?.string("command"))
        assertEquals("sess-1", ctx.sessionId)
    }

    @Test
    fun `parses Notification message and title`() {
        val ctx = broker.parse(callback("cb", input = input("Notification") {
            put("message", "Claude needs your input")
            put("title", "Claude Code")
            put("notification_type", "permission")
        }))!!
        assertEquals("Notification", ctx.hookEventName)
        assertEquals("Claude needs your input", ctx.message)
        assertEquals("Claude Code", ctx.title)
    }

    @Test
    fun `parses FileChanged path and event`() {
        val ctx = broker.parse(callback("cb", input = input("FileChanged") {
            put("file_path", "/proj/src/Main.kt")
            put("event", "change")
        }))!!
        assertEquals("/proj/src/Main.kt", ctx.filePath)
        assertEquals("change", ctx.fileEvent)
    }

    @Test
    fun `parses SessionStart source and PreCompact trigger`() {
        val start = broker.parse(callback("cb", input = input("SessionStart") { put("source", "resume") }))!!
        assertEquals("resume", start.source)
        val pre = broker.parse(callback("cb", input = input("PreCompact") { put("trigger", "auto") }))!!
        assertEquals("auto", pre.trigger)
    }

    @Test
    fun `parse returns null on malformed frame`() {
        assertNull(broker.parse(buildJsonObject { put("subtype", "hook_callback") }))
        assertNull(broker.parse(buildJsonObject { put("input", buildJsonObject { put("x", 1) }) }))
    }

    // --- default decisions ---

    @Test
    fun `default handlers Continue for every default event`() {
        for (event in listOf("PreToolUse", "PermissionRequest", "Notification", "FileChanged",
            "SessionStart", "SessionEnd", "Stop", "PreCompact", "PostCompact", "UserPromptSubmit")) {
            val ctx = broker.parse(callback("cb", input = input(event)))!!
            assertInstanceOf(HookDecision.Continue::class.java, broker.decide(ctx), event)
        }
    }

    @Test
    fun `unknown event falls back to Continue`() {
        val ctx = broker.parse(callback("cb", input = input("SomethingNew")))!!
        assertInstanceOf(HookDecision.Continue::class.java, broker.decide(ctx))
    }

    @Test
    fun `registered handler overrides default`() {
        val b = HookBroker()
        b.register("PreToolUse") { ctx ->
            if (ctx.toolName == "Bash") HookDecision.Block("no shell") else HookDecision.Continue
        }
        val ctx = b.parse(callback("cb", input = input("PreToolUse") { put("tool_name", "Bash") }))!!
        val decision = b.decide(ctx)
        assertEquals("no shell", (decision as HookDecision.Block).reason)
    }

    // --- buildResponse wire shapes ---

    @Test
    fun `Continue maps to continue true`() {
        val out = broker.buildResponse("cb1", HookDecision.Continue, "PreToolUse")
        assertEquals(true, out.bool("continue"))
        assertEquals("cb1", out.string("callback_id"))
    }

    @Test
    fun `PreToolUse Block maps to deny permissionDecision`() {
        val out = broker.buildResponse("cb", HookDecision.Block("dangerous"), "PreToolUse")
        val hso = out.obj("hookSpecificOutput")
        assertEquals("PreToolUse", hso.string("hookEventName"))
        assertEquals("deny", hso.string("permissionDecision"))
        assertEquals("dangerous", hso.string("permissionDecisionReason"))
    }

    @Test
    fun `PreToolUse Modify maps to allow with updatedInput`() {
        val updated = buildJsonObject { put("command", "ls -la") }
        val out = broker.buildResponse("cb", HookDecision.Modify(updated), "PreToolUse")
        val hso = out.obj("hookSpecificOutput")
        assertEquals("allow", hso.string("permissionDecision"))
        assertEquals("ls -la", hso.obj("updatedInput").string("command"))
    }

    @Test
    fun `PermissionRequest Block maps to nested deny decision`() {
        val out = broker.buildResponse("cb", HookDecision.Block("policy"), "PermissionRequest")
        val decision = out.obj("hookSpecificOutput").obj("decision")
        assertEquals("deny", decision.string("behavior"))
        assertEquals("policy", decision.string("message"))
    }

    @Test
    fun `PermissionRequest Modify maps to nested allow decision`() {
        val updated = buildJsonObject { put("file_path", "/proj/x") }
        val out = broker.buildResponse("cb", HookDecision.Modify(updated), "PermissionRequest")
        val decision = out.obj("hookSpecificOutput").obj("decision")
        assertEquals("allow", decision.string("behavior"))
        assertEquals("/proj/x", decision.obj("updatedInput").string("file_path"))
    }

    @Test
    fun `generic event Block maps to top-level decision block`() {
        val out = broker.buildResponse("cb", HookDecision.Block("stop"), "Stop")
        assertEquals("block", out.string("decision"))
        assertEquals("stop", out.string("reason"))
    }

    @Test
    fun `generic event Modify degrades to continue`() {
        val out = broker.buildResponse("cb", HookDecision.Modify(buildJsonObject {}), "Notification")
        assertEquals(true, out.bool("continue"))
        assertNull(out["hookSpecificOutput"])
    }

    @Test
    fun `Annotate sets systemMessage and additionalContext for annotatable events`() {
        val out = broker.buildResponse("cb", HookDecision.Annotate("remember X"), "UserPromptSubmit")
        assertEquals("remember X", out.string("systemMessage"))
        assertEquals("remember X", out.obj("hookSpecificOutput").string("additionalContext"))
    }

    @Test
    fun `Annotate omits hookSpecificOutput for non-annotatable events`() {
        val out = broker.buildResponse("cb", HookDecision.Annotate("note"), "Stop")
        assertEquals("note", out.string("systemMessage"))
        assertNull(out["hookSpecificOutput"])
    }

    // --- side effects ---

    @Test
    fun `Notification yields NotifyUser side effect`() {
        val ctx = broker.parse(callback("cb", input = input("Notification") {
            put("message", "hi"); put("title", "T")
        }))!!
        val fx = broker.sideEffects(ctx, HookDecision.Continue)
        val notify = fx.filterIsInstance<HookSideEffect.NotifyUser>().single()
        assertEquals("hi", notify.message)
        assertEquals("T", notify.title)
    }

    @Test
    fun `FileChanged yields RefreshFile side effect`() {
        val ctx = broker.parse(callback("cb", input = input("FileChanged") {
            put("file_path", "/proj/a.kt"); put("event", "add")
        }))!!
        val refresh = broker.sideEffects(ctx, HookDecision.Continue)
            .filterIsInstance<HookSideEffect.RefreshFile>().single()
        assertEquals("/proj/a.kt", refresh.path)
        assertEquals("add", refresh.event)
    }

    @Test
    fun `PreCompact yields a transcript note`() {
        val ctx = broker.parse(callback("cb", input = input("PreCompact") { put("trigger", "manual") }))!!
        val note = broker.sideEffects(ctx, HookDecision.Continue)
            .filterIsInstance<HookSideEffect.TranscriptNote>().single()
        assertTrue(note.text.contains("Compacting"))
        assertTrue(note.text.contains("manual"))
    }

    @Test
    fun `SessionStart yields a lifecycle marker`() {
        val ctx = broker.parse(callback("cb", input = input("SessionStart") { put("source", "startup") }))!!
        val marker = broker.sideEffects(ctx, HookDecision.Continue)
            .filterIsInstance<HookSideEffect.Marker>().single()
        assertEquals("SessionStart", marker.event)
        assertEquals("startup", marker.detail)
    }

    @Test
    fun `Block decision adds a transcript note explaining the block`() {
        val ctx = broker.parse(callback("cb", input = input("PreToolUse") { put("tool_name", "Bash") }))!!
        val note = broker.sideEffects(ctx, HookDecision.Block("denied by rule"))
            .filterIsInstance<HookSideEffect.TranscriptNote>().single()
        assertTrue(note.text.contains("denied by rule"))
    }

    @Test
    fun `blank notification message yields no side effect`() {
        val ctx = broker.parse(callback("cb", input = input("Notification") { put("message", "  ") }))!!
        assertTrue(broker.sideEffects(ctx, HookDecision.Continue).isEmpty())
    }
}
