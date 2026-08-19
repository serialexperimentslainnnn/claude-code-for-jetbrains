package dev.lain.claudejb.ui.jcef

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JcefBridgeTest {

    @Test
    fun `parse simple verbs`() {
        assertTrue(JcefBridge.parse("""{"type":"interrupt"}""") is JcefBridge.Msg.Interrupt)
        assertTrue(JcefBridge.parse("""{"type":"cycleMode"}""") is JcefBridge.Msg.CycleMode)
        assertTrue(JcefBridge.parse("""{"type":"ready"}""") is JcefBridge.Msg.Ready)
    }

    @Test
    fun `parse send carries text`() {
        val m = JcefBridge.parse("""{"type":"send","text":"hello world"}""")
        assertEquals("hello world", (m as JcefBridge.Msg.Send).text)
    }

    @Test
    fun `parse change messages`() {
        assertEquals(
            "claude-opus-4-8",
            (JcefBridge.parse("""{"type":"changeModel","value":"claude-opus-4-8"}""") as JcefBridge.Msg.ChangeModel).value,
        )
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
        val rq = JcefBridge.parse(
            """{"type":"resolveQuestion","id":"r9","answers":{"Q1":"A","Q2":"B"}}""",
        ) as JcefBridge.Msg.ResolveQuestion
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
            """{"type":"resolveElicitation","id":"e1","action":"accept","content":{"token":"abc","count":3}}""",
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
            """{"type":"resolveElicitation","id":"e2","action":"decline"}""",
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
    fun `parse attach carries name mediaType and base64`() {
        val m = JcefBridge.parse(
            """{"type":"attach","name":"shot.png","mediaType":"image/png","base64":"AAAA"}""",
        ) as JcefBridge.Msg.Attach
        assertEquals("shot.png", m.name)
        assertEquals("image/png", m.mediaType)
        assertEquals("AAAA", m.base64)
    }

    @Test
    fun `parse treeChildren carries the root-relative path and the picker`() {
        val m = JcefBridge.parse(
            """{"type":"treeChildren","path":"src/main","mode":"files"}""",
        ) as JcefBridge.Msg.TreeChildren
        assertEquals("src/main", m.path)
        assertEquals("files", m.mode)
        val dirs = JcefBridge.parse(
            """{"type":"treeExpand","path":"src","mode":"directories"}""",
        ) as JcefBridge.Msg.TreeExpand
        assertEquals("src", dirs.path)
        assertEquals("directories", dirs.mode)
    }

    @Test
    fun `an absent path is the project ROOT, not a missing field`() {
        val m = JcefBridge.parse("""{"type":"treeChildren","mode":"files"}""") as JcefBridge.Msg.TreeChildren
        assertEquals("", m.path)
    }

    @Test
    fun `a path that is not a string is dropped, never thrown on`() {
        val obj = JcefBridge.parse("""{"type":"treeChildren","path":{"nope":1},"mode":"files"}""")
        assertEquals("", (obj as JcefBridge.Msg.TreeChildren).path)
        val arr = JcefBridge.parse("""{"type":"treeExpand","path":["a"],"mode":"files"}""")
        assertEquals("", (arr as JcefBridge.Msg.TreeExpand).path)
    }

    @Test
    fun `an absolute path rides through verbatim — containment is ProjectTree's single gate`() {
        val m = JcefBridge.parse(
            """{"type":"treeChildren","path":"/etc/passwd","mode":"files"}""",
        ) as JcefBridge.Msg.TreeChildren
        assertEquals("/etc/passwd", m.path)
        val up = JcefBridge.parse("""{"type":"attachPaths","paths":["../../etc/passwd"]}""")
        assertEquals(listOf("../../etc/passwd"), (up as JcefBridge.Msg.AttachPaths).paths)
    }

    @Test
    fun `parse attachPaths is a batch, and total on the shapes a batch can arrive in`() {
        val m = JcefBridge.parse(
            """{"type":"attachPaths","paths":["README.md","src/App.kt"]}""",
        ) as JcefBridge.Msg.AttachPaths
        assertEquals(listOf("README.md", "src/App.kt"), m.paths)
        val junk = JcefBridge.parse(
            """{"type":"attachPaths","paths":["ok.kt","",{"a":1}]}""",
        ) as JcefBridge.Msg.AttachPaths
        assertEquals(listOf("ok.kt"), junk.paths)
        assertTrue((JcefBridge.parse("""{"type":"attachPaths"}""") as JcefBridge.Msg.AttachPaths).paths.isEmpty())
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
    fun `parse gitAction carries the id and the commit hash`() {
        val m = JcefBridge.parse(
            """{"type":"gitAction","id":"commitRevert","hash":"8933592ffee1"}""",
        ) as JcefBridge.Msg.GitAction
        assertEquals("commitRevert", m.id)
        assertEquals("8933592ffee1", m.hash)
    }

    @Test
    fun `parse gitAction without a hash reads as no commit, not as a missing field`() {
        val m = JcefBridge.parse("""{"type":"gitAction","id":"commit"}""") as JcefBridge.Msg.GitAction
        assertEquals("commit", m.id)
        assertEquals("", m.hash)
    }

    @Test
    fun `a turn carries the conversation it is for, and the ordinary one carries none`() {
        assertEquals(JcefBridge.Msg.Send("hello"), JcefBridge.parse("""{"type":"send","text":"hello"}"""))
        assertEquals(
            JcefBridge.Msg.Send("squash those two", JcefBridge.SCOPE_GIT),
            JcefBridge.parse("""{"type":"send","text":"squash those two","scope":"git"}"""),
        )
        assertEquals(JcefBridge.Msg.Interrupt(), JcefBridge.parse("""{"type":"interrupt"}"""))
        assertEquals(
            JcefBridge.Msg.Interrupt(JcefBridge.SCOPE_GIT),
            JcefBridge.parse("""{"type":"interrupt","scope":"git"}"""),
        )
    }

    @Test
    fun `parse a card resolution carries the conversation it belongs to`() {
        val own = JcefBridge.parse("""{"type":"resolvePermission","id":"r1","allow":true}""")
        assertEquals(JcefBridge.Msg.ResolvePermission("r1", true, ""), own)
        val git = JcefBridge.parse("""{"type":"resolvePermission","id":"r1","allow":true,"scope":"git"}""")
        assertEquals(JcefBridge.Msg.ResolvePermission("r1", true, JcefBridge.SCOPE_GIT), git)
    }

    @Test
    fun `parse installClaude carries the method id`() {
        val m = JcefBridge.parse("""{"type":"installClaude","method":"apt"}""") as JcefBridge.Msg.InstallClaude
        assertEquals("apt", m.method)
    }

    @Test
    fun `parse setBinaryPath carries the path`() {
        val m = JcefBridge.parse("""{"type":"setBinaryPath","path":"/opt/claude/claude"}""") as JcefBridge.Msg.SetBinaryPath
        assertEquals("/opt/claude/claude", m.path)
    }

    @Test
    fun `parse recheckBinary`() {
        assertEquals(JcefBridge.Msg.RecheckBinary, JcefBridge.parse("""{"type":"recheckBinary"}"""))
    }

    @Test
    fun `parse loginSubscription cancelLogin dismissAuth logout`() {
        assertEquals(JcefBridge.Msg.LoginSubscription, JcefBridge.parse("""{"type":"loginSubscription"}"""))
        assertEquals(JcefBridge.Msg.CancelLogin, JcefBridge.parse("""{"type":"cancelLogin"}"""))
        assertEquals(JcefBridge.Msg.DismissAuth, JcefBridge.parse("""{"type":"dismissAuth"}"""))
        assertEquals(JcefBridge.Msg.Logout, JcefBridge.parse("""{"type":"logout"}"""))
    }

    @Test
    fun `parse useApiKey and submitLoginCode carry their secret verbatim`() {
        val key = JcefBridge.parse("""{"type":"useApiKey","key":"sk-ant-test-123"}""") as JcefBridge.Msg.UseApiKey
        assertEquals("sk-ant-test-123", key.key)
        val code = JcefBridge.parse("""{"type":"submitLoginCode","code":"ABC-42"}""") as JcefBridge.Msg.SubmitLoginCode
        assertEquals("ABC-42", code.code)
    }

    @Test
    fun `jsString escapes what would break out of a host exec call`() {
        assertEquals("\"plain\"", JcefBridge.jsString("plain"))
        assertEquals("\"a\\\"b\"", JcefBridge.jsString("a\"b"))
        assertEquals("\"line\\nbreak\"", JcefBridge.jsString("line\nbreak"))
    }

    @Test
    fun `parse resolveLinks carries the row id and both candidate lists`() {
        val m = JcefBridge.parse(
            """{"type":"resolveLinks","rowId":42,"paths":["src/Foo.kt","a.py:7"],"symbols":["PermissionBroker"]}""",
        ) as JcefBridge.Msg.ResolveLinks
        assertEquals(42L, m.rowId)
        assertEquals(listOf("src/Foo.kt", "a.py:7"), m.paths)
        assertEquals(listOf("PermissionBroker"), m.symbols)
    }

    @Test
    fun `parse resolveLinks is total on missing, blank and non-string candidates`() {
        val m = JcefBridge.parse("""{"type":"resolveLinks"}""") as JcefBridge.Msg.ResolveLinks
        assertEquals(-1L, m.rowId)
        assertTrue(m.paths.isEmpty())
        assertTrue(m.symbols.isEmpty())
        val junk = JcefBridge.parse(
            """{"type":"resolveLinks","rowId":1,"paths":["ok.kt","",{"a":1}],"symbols":"nope"}""",
        ) as JcefBridge.Msg.ResolveLinks
        assertEquals(listOf("ok.kt"), junk.paths)
        assertTrue(junk.symbols.isEmpty())
    }

    @Test
    fun `parse unknown type and malformed are total`() {
        assertTrue(JcefBridge.parse("""{"type":"wat"}""") is JcefBridge.Msg.Unknown)
        assertTrue(JcefBridge.parse("not json") is JcefBridge.Msg.Unknown)
        assertTrue(JcefBridge.parse("""{"notype":1}""") is JcefBridge.Msg.Unknown)
    }
}
