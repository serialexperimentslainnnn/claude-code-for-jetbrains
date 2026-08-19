package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.permission.ElicitationCard
import dev.lain.claudejb.permission.PendingPermission
import dev.lain.claudejb.protocol.AskOption
import dev.lain.claudejb.protocol.AskQuestion
import dev.lain.claudejb.protocol.ElicitField
import dev.lain.claudejb.session.Speaker
import dev.lain.claudejb.session.ToolState
import dev.lain.claudejb.session.TranscriptEntry
import dev.lain.claudejb.ui.LinkResolver
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JcefPayloadTest {

    @Test
    fun `entryJson carries id order speaker text state elapsed and omits null optionals`() {
        val e = TranscriptEntry(7L, Speaker.ASSISTANT, "**hi**")
        val o = JcefTranscriptPayload.entryJson(e, order = 3)
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
            1L,
            Speaker.TOOL,
            "Read(App.kt)",
            meta = "error",
            toolUseId = "tu1",
            parentToolUseId = "agent1",
            toolState = ToolState.RUNNING,
        )
        val o = JcefTranscriptPayload.entryJson(e, order = 0)
        assertEquals("error", o["meta"]!!.jsonPrimitive.content)
        assertEquals("tu1", o["toolUseId"]!!.jsonPrimitive.content)
        assertEquals("agent1", o["parent"]!!.jsonPrimitive.content)
        assertEquals("RUNNING", o["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun `batchJson is a JSON array preserving order pairs`() {
        val a = TranscriptEntry(1L, Speaker.USER, "one")
        val b = TranscriptEntry(2L, Speaker.ASSISTANT, "two")
        val arr = Json.parseToJsonElement(JcefTranscriptPayload.batchJson(listOf(a to 5, b to 6))).jsonArray
        assertEquals(2, arr.size)
        assertEquals(5, arr[0].jsonObject["order"]!!.jsonPrimitive.int)
        assertEquals(2, arr[1].jsonObject["id"]!!.jsonPrimitive.int)
    }

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
        val o = JcefCardPayload.permissionJson(perm(reviewable = true))
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
            question = "Pick one",
            header = "Choice",
            options = listOf(AskOption("A", "first", preview = "pa"), AskOption("B", "second")),
            multiSelect = true,
        )
        val o = JcefCardPayload.permissionJson(perm(questions = listOf(q)))
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
        val o = JcefCardPayload.permissionJson(perm(isPlan = true, planText = "## Plan\n- do it"))
        assertTrue(o["isPlan"]!!.jsonPrimitive.boolean)
        assertEquals("## Plan\n- do it", o["planText"]!!.jsonPrimitive.content)
    }

    @Test
    fun `permissionJson elicitation card carries fields`() {
        val card = ElicitationCard(
            serverName = "srv",
            message = "Enter a key",
            description = null,
            mode = "form",
            url = null,
            fields = listOf(ElicitField("token", "string", "Token", required = true)),
        )
        val o = JcefCardPayload.permissionJson(perm(elicitation = card))
        val e = o["elicitation"]!!.jsonObject
        assertEquals("srv", e["serverName"]!!.jsonPrimitive.content)
        assertEquals("form", e["mode"]!!.jsonPrimitive.content)
        val f0 = e["fields"]!!.jsonArray[0].jsonObject
        assertEquals("token", f0["name"]!!.jsonPrimitive.content)
        assertTrue(f0["required"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `permissionsJson is an array`() {
        val arr = Json.parseToJsonElement(JcefCardPayload.permissionsJson(listOf(perm(), perm()))).jsonArray
        assertEquals(2, arr.size)
    }

    @Test
    fun `entryJson carries the project-relative filePath of a file tool and omits it elsewhere`() {
        val tool = TranscriptEntry(
            1L,
            Speaker.TOOL,
            "Read(src/Foo.kt)",
            meta = "Read",
            toolUseId = "t1",
            filePath = "src/Foo.kt",
        )
        assertEquals("src/Foo.kt", JcefTranscriptPayload.entryJson(tool, 0)["filePath"]!!.jsonPrimitive.content)
        assertNull(JcefTranscriptPayload.entryJson(TranscriptEntry(2L, Speaker.ASSISTANT, "hi"), 1)["filePath"])
    }

    @Test
    fun `linksJson answers with the row id and only the resolved tokens`() {
        val json = JcefTranscriptPayload.linksJson(
            7L,
            listOf(
                LinkResolver.Resolved("src/Foo.kt", "src/Foo.kt", null),
                LinkResolver.Resolved("PermissionBroker", "src/permission/PermissionBroker.kt", 31),
            ),
        )
        val o = Json.parseToJsonElement(json).jsonObject
        assertEquals(7, o["rowId"]!!.jsonPrimitive.int)
        val links = o["links"]!!.jsonArray
        assertEquals(2, links.size)
        assertEquals("src/Foo.kt", links[0].jsonObject["token"]!!.jsonPrimitive.content)
        assertNull(links[0].jsonObject["line"])
        assertEquals("PermissionBroker", links[1].jsonObject["token"]!!.jsonPrimitive.content)
        assertEquals("src/permission/PermissionBroker.kt", links[1].jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals(31, links[1].jsonObject["line"]!!.jsonPrimitive.int)
    }

    @Test
    fun `linksJson with nothing resolved is an empty link list, never null`() {
        val o = Json.parseToJsonElement(JcefTranscriptPayload.linksJson(3L, emptyList())).jsonObject
        assertEquals(3, o["rowId"]!!.jsonPrimitive.int)
        assertTrue(o["links"]!!.jsonArray.isEmpty())
    }
}
