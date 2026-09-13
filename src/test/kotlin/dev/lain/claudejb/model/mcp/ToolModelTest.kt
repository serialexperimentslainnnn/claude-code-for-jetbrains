package dev.lain.claudejb.model.mcp

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ToolModelTest {

    @Test
    fun `a domain never exposes more than four tools`() {
        val tools = (1..5).map { Tool(ToolSpec("t$it", "tool $it")) { ToolResult("") } }
        assertThrows<IllegalArgumentException> { ToolDomain("big", "too many", tools) }
        ToolDomain("fits", "four", tools.take(ToolDomain.MAX_TOOLS))
    }

    @Test
    fun `tool and domain names are unique across the catalog`() {
        val a = ToolDomain("a", "", listOf(Tool(ToolSpec("same", "")) { ToolResult("") }))
        val b = ToolDomain("b", "", listOf(Tool(ToolSpec("same", "")) { ToolResult("") }))
        assertThrows<IllegalArgumentException> { ToolCatalog(listOf(a, b)) }
        assertThrows<IllegalArgumentException> { ToolCatalog(listOf(a, ToolDomain("a", "", emptyList()))) }
        assertEquals("same", ToolCatalog(listOf(a)).tool("same")?.spec?.name)
    }

    @Test
    fun `the input schema is strict and lists the required parameters`() {
        val schema = ToolSpec("x", "", listOf(Param("path", "where"), Param("limit", "how many", type = "integer", required = false))).inputSchema
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        assertEquals("false", schema["additionalProperties"]?.jsonPrimitive?.content)
        assertEquals(listOf("path"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("integer", schema["properties"]!!.jsonObject["limit"]!!.jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `arguments are read by name with typed errors`() {
        val args = ToolArgs(parse("""{"path":"a.kt","limit":"12","deep":"true","bad":"x"}"""))
        assertEquals("a.kt", args.string("path"))
        assertEquals(12, args.int("limit", 1))
        assertEquals(5, args.int("missing", 5))
        assertTrue(args.boolean("deep", false))
        assertThrows<ToolException> { args.string("missing") }
        assertThrows<ToolException> { args.int("bad", 0) }
        assertThrows<ToolException> { args.boolean("bad", true) }
    }

    @Test
    fun `a list of strings is read whole, and anything else in it is a typed error`() {
        val args = ToolArgs(parse("""{"paths":["a.kt","b.kt"],"mixed":["a",1],"one":"a.kt"}"""))
        assertEquals(listOf("a.kt", "b.kt"), args.strings("paths"))
        assertEquals(emptyList<String>(), args.strings("missing"))
        assertThrows<ToolException> { args.strings("mixed") }
        assertThrows<ToolException> { args.strings("one") }
    }

    @Test
    fun `run hands the tool the client's tool use id from the call's meta, and null without it`() = runBlocking {
        var seen: String? = "unset"
        val tool = Tool(ToolSpec("t", "")) {
            seen = it.toolUseId
            ToolResult("ok")
        }
        val catalog = ToolCatalog(listOf(ToolDomain("d", "", listOf(tool))))
        val meta = MetaTools(catalog, { _, _ -> null }, OutputBudget())
        val call = parse("""{"tool":"t"}""")
        meta.call("run", call, parse("""{"claudecode/toolUseId":"toolu_1"}"""))
        assertEquals("toolu_1", seen)
        meta.call("run", call)
        assertEquals(null, seen)
    }

    @Test
    fun `a tool that overruns its own timeout answers with an error naming it, and a fast one is untouched`() = runBlocking {
        val slow = Tool(ToolSpec("slow", "", timeoutMillis = 50)) {
            delay(10_000)
            ToolResult("late")
        }
        val fast = Tool(ToolSpec("fast", "", timeoutMillis = 50)) { ToolResult("done") }
        val meta = MetaTools(ToolCatalog(listOf(ToolDomain("d", "", listOf(slow, fast)))), { _, _ -> null }, OutputBudget())
        val late = meta.call("run", parse("""{"tool":"slow"}"""))!!
        assertTrue(late.isError)
        assertTrue(late.text.contains("slow did not finish within 0 s")) { late.text }
        assertEquals("done", meta.call("run", parse("""{"tool":"fast"}"""))!!.text)
    }

    @Test
    fun `a tool that blows up with anything but a cancellation answers in band, so the request never hangs`() = runBlocking {
        val missing = Tool(ToolSpec("missing", "")) { throw NoSuchMethodError("GitFileUtils.addPaths(...)") }
        val broken = Tool(ToolSpec("broken", "")) { throw IllegalStateException("no document") }
        val meta = MetaTools(ToolCatalog(listOf(ToolDomain("d", "", listOf(missing, broken)))), { _, _ -> null }, OutputBudget())
        val api = meta.call("run", parse("""{"tool":"missing"}"""))!!
        assertTrue(api.isError)
        assertTrue(api.text.contains("missing needs an API this IDE build does not have: GitFileUtils.addPaths(...)")) { api.text }
        val bug = meta.call("run", parse("""{"tool":"broken"}"""))!!
        assertTrue(bug.isError)
        assertTrue(bug.text.contains("broken failed: IllegalStateException: no document")) { bug.text }
    }

    @Test
    fun `errors are TOON too`() {
        val error = ToolResult.error("no such file")
        assertTrue(error.isError)
        assertEquals("error: no such file", error.text)
        assertFalse(ToolResult.toon(parse("""{"a":1}""")).isError)
    }

    @Test
    fun `the budget cuts on a line boundary and says so in a comment line`() {
        val budget = OutputBudget(120)
        val text = (1..40).joinToString("\n") { "line $it" }
        val fitted = budget.fit(text)
        assertTrue(fitted.length <= 120) { fitted }
        assertTrue(fitted.lines().last().startsWith("# truncated: ")) { fitted }
        assertTrue(fitted.lines().dropLast(1).all { it.startsWith("line ") }) { fitted }
        assertEquals("short", budget.fit("short"))
    }

    @Test
    fun `tokens rotate with an overlap window and compare in constant shape`() {
        var now = 0L
        val ring = TokenRing(overlapMillis = 100, clock = { now })
        val first = ring.token
        assertTrue(ring.accepts(first))
        assertFalse(ring.accepts(null))
        assertFalse(ring.accepts(first.dropLast(1) + "x"))
        val second = ring.rotate()
        assertTrue(ring.accepts(second))
        assertTrue(ring.accepts(first))
        now = 101
        assertFalse(ring.accepts(first))
        assertTrue(ring.accepts(second))
        assertTrue(first.length >= 43 && first != second)
    }

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
