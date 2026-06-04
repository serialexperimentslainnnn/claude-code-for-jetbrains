package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.permission.ElicitationCard
import dev.lain.claudejb.permission.PendingPermission
import dev.lain.claudejb.protocol.AskOption
import dev.lain.claudejb.protocol.AskQuestion
import dev.lain.claudejb.protocol.ElicitField
import dev.lain.claudejb.session.Speaker
import dev.lain.claudejb.session.ToolState
import dev.lain.claudejb.session.TranscriptEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-JVM coverage of the JCEF bridge protocol — [JcefBridge] serialization (transcript rows / permission
 * cards → the frontend's JSON shapes) and inbound parsing (every `window.__ccSend` message `type` → a typed
 * [JcefBridge.Msg]). No platform/browser is involved, so this is the load-bearing contract test.
 */
class JcefBridgeTest {

    // ── entry serialization ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `entryJson carries id order speaker text state elapsed and omits null optionals`() {
        val e = TranscriptEntry(7L, Speaker.ASSISTANT, "**hi**")
        val o = JcefBridge.entryJson(e, order = 3)
        assertEquals(7, o["id"]!!.jsonPrimitive.int)
        assertEquals(3, o["order"]!!.jsonPrimitive.int)
        assertEquals("ASSISTANT", o["speaker"]!!.jsonPrimitive.content)
        assertEquals("**hi**", o["text"]!!.jsonPrimitive.content)
        assertEquals("FINISHED", o["state"]!!.jsonPrimitive.content)
        assertTrue(o.containsKey("elapsed"))
        assertFalse(o.containsKey("meta"))
        assertFalse(o.containsKey("toolUseId"))
        assertFalse(o.containsKey("parent"))
    }

    @Test
    fun `entryJson includes meta toolUseId and parent when present`() {
        val e = TranscriptEntry(
            1L, Speaker.TOOL, "Read(App.kt)",
            meta = "error", toolUseId = "tu1", parentToolUseId = "agent1", toolState = ToolState.RUNNING,
        )
        val o = JcefBridge.entryJson(e, order = 0)
        assertEquals("error", o["meta"]!!.jsonPrimitive.content)
        assertEquals("tu1", o["toolUseId"]!!.jsonPrimitive.content)
        assertEquals("agent1", o["parent"]!!.jsonPrimitive.content)
        assertEquals("RUNNING", o["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun `batchJson is a JSON array preserving order pairs`() {
        val a = TranscriptEntry(1L, Speaker.USER, "one")
        val b = TranscriptEntry(2L, Speaker.ASSISTANT, "two")
        val arr = Json.parseToJsonElement(JcefBridge.batchJson(listOf(a to 5, b to 6))).jsonArray
        assertEquals(2, arr.size)
        assertEquals(5, arr[0].jsonObject["order"]!!.jsonPrimitive.int)
        assertEquals(2, arr[1].jsonObject["id"]!!.jsonPrimitive.int)
    }

    // ── permission serialization ─────────────────────────────────────────────────────────────────────────

    private fun perm(
        reviewable: Boolean = false,
        questions: List<AskQuestion>? = null,
        isPlan: Boolean = false,
        planText: String? = null,
        elicitation: ElicitationCard? = null,
    ) = PendingPermission(
        requestId = "r1", toolName = "Edit", input = buildJsonObject { put("file_path", "App.kt") },
        title = "Edit App.kt", summary = "writes App.kt", reviewable = reviewable,
        questions = questions, isPlan = isPlan, planText = planText, elicitation = elicitation,
    )

    @Test
    fun `permissionJson standard card`() {
        val o = JcefBridge.permissionJson(perm(reviewable = true))
        assertEquals("r1", o["id"]!!.jsonPrimitive.content)
        assertEquals("Edit", o["tool"]!!.jsonPrimitive.content)
        assertTrue(o["reviewable"]!!.jsonPrimitive.boolean)
        assertFalse(o["isPlan"]!!.jsonPrimitive.boolean)
        assertFalse(o.containsKey("questions"))
        assertFalse(o.containsKey("elicitation"))
    }

    @Test
    fun `permissionJson AskUserQuestion card carries questions and options`() {
        val q = AskQuestion(
            question = "Pick one", header = "Choice",
            options = listOf(AskOption("A", "first", preview = "pa"), AskOption("B", "second")),
            multiSelect = true,
        )
        val o = JcefBridge.permissionJson(perm(questions = listOf(q)))
        val qs = o["questions"]!!.jsonArray
        assertEquals(1, qs.size)
        val q0 = qs[0].jsonObject
        assertEquals("Choice", q0["header"]!!.jsonPrimitive.content)
        assertTrue(q0["multiSelect"]!!.jsonPrimitive.boolean)
        val opts = q0["options"]!!.jsonArray
        assertEquals("A", opts[0].jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals("pa", opts[0].jsonObject["preview"]!!.jsonPrimitive.content)
        assertFalse(opts[1].jsonObject.containsKey("preview"))
    }

    @Test
    fun `permissionJson plan card carries planText`() {
        val o = JcefBridge.permissionJson(perm(isPlan = true, planText = "## Plan\n- do it"))
        assertTrue(o["isPlan"]!!.jsonPrimitive.boolean)
        assertEquals("## Plan\n- do it", o["planText"]!!.jsonPrimitive.content)
    }

    @Test
    fun `permissionJson elicitation card carries fields`() {
        val card = ElicitationCard(
            serverName = "srv", message = "Enter a key", description = null, mode = "form",
            url = null, fields = listOf(ElicitField("token", "string", "Token", required = true)),
        )
        val o = JcefBridge.permissionJson(perm(elicitation = card))
        val e = o["elicitation"]!!.jsonObject
        assertEquals("srv", e["serverName"]!!.jsonPrimitive.content)
        assertEquals("form", e["mode"]!!.jsonPrimitive.content)
        val f0 = e["fields"]!!.jsonArray[0].jsonObject
        assertEquals("token", f0["name"]!!.jsonPrimitive.content)
        assertTrue(f0["required"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `permissionsJson is an array`() {
        val arr = Json.parseToJsonElement(JcefBridge.permissionsJson(listOf(perm(), perm()))).jsonArray
        assertEquals(2, arr.size)
    }

    // ── inbound parsing ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `parse simple verbs`() {
        assertTrue(JcefBridge.parse("""{"type":"interrupt"}""") is JcefBridge.Msg.Interrupt)
        assertTrue(JcefBridge.parse("""{"type":"cycleMode"}""") is JcefBridge.Msg.CycleMode)
        assertTrue(JcefBridge.parse("""{"type":"ready"}""") is JcefBridge.Msg.Ready)
        assertTrue(JcefBridge.parse("""{"type":"palette"}""") is JcefBridge.Msg.OpenPalette)
    }

    @Test
    fun `parse send carries text`() {
        val m = JcefBridge.parse("""{"type":"send","text":"hello world"}""")
        assertEquals("hello world", (m as JcefBridge.Msg.Send).text)
    }

    @Test
    fun `parse change messages`() {
        assertEquals("claude-opus-4-8", (JcefBridge.parse("""{"type":"changeModel","value":"claude-opus-4-8"}""") as JcefBridge.Msg.ChangeModel).value)
        assertNull((JcefBridge.parse("""{"type":"changeModel"}""") as JcefBridge.Msg.ChangeModel).value)
        assertEquals("plan", (JcefBridge.parse("""{"type":"changeMode","wire":"plan"}""") as JcefBridge.Msg.ChangeMode).wire)
        assertNull((JcefBridge.parse("""{"type":"changeEffort","value":null}""") as JcefBridge.Msg.ChangeEffort).value)
        assertEquals("high", (JcefBridge.parse("""{"type":"changeEffort","value":"high"}""") as JcefBridge.Msg.ChangeEffort).value)
        assertTrue((JcefBridge.parse("""{"type":"changeThinking","on":true}""") as JcefBridge.Msg.ChangeThinking).on)
        assertEquals("deepseek", (JcefBridge.parse("""{"type":"changeProvider","id":"deepseek"}""") as JcefBridge.Msg.ChangeProvider).id)
        assertEquals(2, (JcefBridge.parse("""{"type":"removeQueued","index":2}""") as JcefBridge.Msg.RemoveQueued).index)
    }

    @Test
    fun `parse permission resolutions`() {
        val rp = JcefBridge.parse("""{"type":"resolvePermission","id":"r9","allow":true}""") as JcefBridge.Msg.ResolvePermission
        assertEquals("r9", rp.id)
        assertTrue(rp.allow)
        val rq = JcefBridge.parse("""{"type":"resolveQuestion","id":"r9","answers":{"Q1":"A","Q2":"B"}}""") as JcefBridge.Msg.ResolveQuestion
        assertEquals(mapOf("Q1" to "A", "Q2" to "B"), rq.answers)
        assertEquals("Edit", (JcefBridge.parse("""{"type":"alwaysAllow","tool":"Edit"}""") as JcefBridge.Msg.AlwaysAllow).tool)
        assertEquals("r9", (JcefBridge.parse("""{"type":"viewDiff","id":"r9"}""") as JcefBridge.Msg.ViewDiff).id)
    }

    @Test
    fun `parse open and copy`() {
        assertEquals("https://x.dev", (JcefBridge.parse("""{"type":"open","url":"https://x.dev"}""") as JcefBridge.Msg.Open).url)
        assertEquals("snippet", (JcefBridge.parse("""{"type":"copy","text":"snippet"}""") as JcefBridge.Msg.Copy).text)
    }

    @Test
    fun `parse resolveElicitation with content carries action and JsonObject content`() {
        val m = JcefBridge.parse(
            """{"type":"resolveElicitation","id":"e1","action":"accept","content":{"token":"abc","count":3}}"""
        ) as JcefBridge.Msg.ResolveElicitation
        assertEquals("e1", m.id)
        assertEquals("accept", m.action)
        val content = m.content
        assertNotNull(content)
        assertEquals("abc", content!!["token"]!!.jsonPrimitive.content)
        assertEquals(3, content["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun `parse resolveElicitation without content has null content`() {
        val m = JcefBridge.parse(
            """{"type":"resolveElicitation","id":"e2","action":"decline"}"""
        ) as JcefBridge.Msg.ResolveElicitation
        assertEquals("e2", m.id)
        assertEquals("decline", m.action)
        assertNull(m.content)
    }

    @Test
    fun `parse removeAttachment carries id`() {
        val m = JcefBridge.parse("""{"type":"removeAttachment","id":"att7"}""") as JcefBridge.Msg.RemoveAttachment
        assertEquals("att7", m.id)
    }

    @Test
    fun `parse pickFiles is the singleton object`() {
        assertTrue(JcefBridge.parse("""{"type":"pickFiles"}""") is JcefBridge.Msg.PickFiles)
    }

    @Test
    fun `parse attach carries name mediaType and base64`() {
        val m = JcefBridge.parse(
            """{"type":"attach","name":"shot.png","mediaType":"image/png","base64":"AAAA"}"""
        ) as JcefBridge.Msg.Attach
        assertEquals("shot.png", m.name)
        assertEquals("image/png", m.mediaType)
        assertEquals("AAAA", m.base64)
    }

    @Test
    fun `parse mcpReconnect carries name`() {
        val m = JcefBridge.parse("""{"type":"mcpReconnect","name":"jetbrains"}""") as JcefBridge.Msg.McpReconnect
        assertEquals("jetbrains", m.name)
    }

    @Test
    fun `parse mcpToggle carries name and enabled flag`() {
        val on = JcefBridge.parse("""{"type":"mcpToggle","name":"srv","enabled":true}""") as JcefBridge.Msg.McpToggle
        assertEquals("srv", on.name)
        assertTrue(on.enabled)
        val off = JcefBridge.parse("""{"type":"mcpToggle","name":"srv","enabled":false}""") as JcefBridge.Msg.McpToggle
        assertEquals("srv", off.name)
        assertFalse(off.enabled)
    }

    @Test
    fun `parse stopTask carries taskId`() {
        val m = JcefBridge.parse("""{"type":"stopTask","taskId":"task42"}""") as JcefBridge.Msg.StopTask
        assertEquals("task42", m.taskId)
    }

    @Test
    fun `parse unknown type and malformed are total`() {
        assertTrue(JcefBridge.parse("""{"type":"wat"}""") is JcefBridge.Msg.Unknown)
        assertTrue(JcefBridge.parse("not json") is JcefBridge.Msg.Unknown)
        assertTrue(JcefBridge.parse("""{"notype":1}""") is JcefBridge.Msg.Unknown)
    }
}
