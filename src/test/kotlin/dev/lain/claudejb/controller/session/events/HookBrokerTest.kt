package dev.lain.claudejb.controller.session.events

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    @Test
    fun `parses PreToolUse with tool name and input`() {
        val req = callback(
            "cb1",
            toolUseId = "tu1",
            input = input("PreToolUse") {
                put("tool_name", "Bash")
                put("tool_input", buildJsonObject { put("command", "ls") })
            },
        )
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
        val ctx = broker.parse(
            callback(
                "cb",
                input = input("Notification") {
                    put("message", "Claude needs your input")
                    put("title", "Claude Code")
                    put("notification_type", "permission")
                },
            ),
        )!!
        assertEquals("Notification", ctx.hookEventName)
        assertEquals("Claude needs your input", ctx.message)
        assertEquals("Claude Code", ctx.title)
    }

    @Test
    fun `parses FileChanged path and event`() {
        val ctx = broker.parse(
            callback(
                "cb",
                input = input("FileChanged") {
                    put("file_path", "/proj/src/Main.kt")
                    put("event", "change")
                },
            ),
        )!!
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
    fun `an explicit JSON null is an absent field, not the word null`() {
        val ctx = broker.parse(
            callback("cb", input = input("FileChanged") { put("file_path", null as String?) }),
        )!!
        assertNull(ctx.filePath)
        assertTrue(broker.sideEffects(ctx).isEmpty())
        assertNull(broker.parse(callback("cb", input = buildJsonObject { put("hook_event_name", null as String?) })))
    }

    @Test
    fun `parse returns null on malformed frame`() {
        assertNull(broker.parse(buildJsonObject { put("subtype", "hook_callback") }))
        assertNull(broker.parse(buildJsonObject { put("input", buildJsonObject { put("x", 1) }) }))
    }

    @Test
    fun `every hook is answered with continue and its callback id`() {
        val out = broker.buildResponse(HookContext("cb1", "PreToolUse"))
        assertEquals(true, out.bool("continue"))
        assertEquals("cb1", out.string("callback_id"))
        assertNull(broker.buildResponse(HookContext("", "PreToolUse"))["callback_id"])
        assertNull(out["hookSpecificOutput"])
    }

    @Test
    fun `the IDE rules callback on UserPromptSubmit carries the rules block as additional context, and nothing else does`() {
        val block = "<ide-integration>\nrules\n</ide-integration>"
        val rules = HookBroker { block }
        val out = rules.buildResponse(HookContext(HookBroker.IDE_RULES_CALLBACK, HookBroker.USER_PROMPT_SUBMIT))
        assertEquals(true, out.bool("continue"))
        val specific = out["hookSpecificOutput"] as JsonObject
        assertEquals(HookBroker.USER_PROMPT_SUBMIT, specific.string("hookEventName"))
        assertEquals(block, specific.string("additionalContext"))
        assertNull(rules.buildResponse(HookContext(HookBroker.IDE_RULES_CALLBACK, "PreToolUse"))["hookSpecificOutput"])
        assertNull(rules.buildResponse(HookContext("hook_0", HookBroker.USER_PROMPT_SUBMIT))["hookSpecificOutput"])
        val silent = HookBroker { "" }
        assertNull(silent.buildResponse(HookContext(HookBroker.IDE_RULES_CALLBACK, HookBroker.USER_PROMPT_SUBMIT))["hookSpecificOutput"])
    }

    @Test
    fun `Notification yields NotifyUser side effect`() {
        val ctx = broker.parse(
            callback(
                "cb",
                input = input("Notification") {
                    put("message", "hi")
                    put("title", "T")
                },
            ),
        )!!
        val notify = broker.sideEffects(ctx).filterIsInstance<HookSideEffect.NotifyUser>().single()
        assertEquals("hi", notify.message)
        assertEquals("T", notify.title)
    }

    @Test
    fun `FileChanged yields RefreshFile side effect`() {
        val ctx = broker.parse(
            callback(
                "cb",
                input = input("FileChanged") {
                    put("file_path", "/proj/a.kt")
                    put("event", "add")
                },
            ),
        )!!
        val refresh = broker.sideEffects(ctx).filterIsInstance<HookSideEffect.RefreshFile>().single()
        assertEquals("/proj/a.kt", refresh.path)
        assertEquals("add", refresh.event)
    }

    @Test
    fun `PreCompact yields a transcript note`() {
        val ctx = broker.parse(callback("cb", input = input("PreCompact") { put("trigger", "manual") }))!!
        val note = broker.sideEffects(ctx).filterIsInstance<HookSideEffect.TranscriptNote>().single()
        assertTrue(note.text.contains("Compacting"))
        assertTrue(note.text.contains("manual"))
    }

    @Test
    fun `SessionStart yields a lifecycle marker`() {
        val ctx = broker.parse(callback("cb", input = input("SessionStart") { put("source", "startup") }))!!
        val marker = broker.sideEffects(ctx).filterIsInstance<HookSideEffect.Marker>().single()
        assertEquals("SessionStart", marker.event)
        assertEquals("startup", marker.detail)
    }

    @Test
    fun `an unknown event and a blank notification yield no side effect`() {
        val unknown = broker.parse(callback("cb", input = input("SomethingNew")))!!
        assertTrue(broker.sideEffects(unknown).isEmpty())
        val blank = broker.parse(callback("cb", input = input("Notification") { put("message", "  ") }))!!
        assertTrue(broker.sideEffects(blank).isEmpty())
    }
}
