package dev.lain.claudejb.model.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

class McpServerTest {

    private val denied = mutableListOf<JsonObject>()

    private val catalog = ToolCatalog(
        listOf(
            ToolDomain(
                "read",
                "Files as the IDE sees them",
                listOf(
                    Tool(ToolSpec("read_file", "Reads a file", listOf(Param("path", "Project-relative path")))) { args ->
                        ToolResult.toon(buildJsonObject { put("path", args.string("path")) })
                    },
                    Tool(ToolSpec("fail", "Always fails")) { throw ToolException("boom") },
                ),
            ),
            ToolDomain("search", "Text and files", emptyList()),
        ),
    )

    private val server = McpServer(
        "code",
        "6.0.0",
        MetaTools(catalog, { _, arguments -> if ("rm -rf" in arguments.toString()) "denied by the guard".also { denied += arguments } else null }, OutputBudget(200)),
    )

    @Test
    fun `a legacy client gets initialize with the version it asked for`() {
        val result = call("initialize", """{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"claude","version":"1"}}""")
        assertEquals("2024-11-05", result["protocolVersion"]?.jsonPrimitive?.content)
        assertEquals("code", result["serverInfo"]!!.jsonObject["name"]?.jsonPrimitive?.content)
        assertTrue(result["capabilities"]!!.jsonObject["tools"]!!.jsonObject["listChanged"]!!.jsonPrimitive.boolean)
        assertEquals("complete", result["resultType"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an unknown legacy version is answered with the newest legacy one`() {
        val result = call("initialize", """{"protocolVersion":"1900-01-01"}""")
        assertEquals("2025-11-25", result["protocolVersion"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a modern client discovers the server`() {
        val result = call("server/discover", modernParams())
        assertEquals(listOf("2026-07-28"), result["supportedVersions"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("code", result["_meta"]!!.jsonObject[McpServer.SERVER_INFO_KEY]!!.jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("public", result["cacheScope"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a modern request with an unsupported version is refused with the supported list`() {
        val response = send("tools/list", """{"_meta":{"${McpServer.PROTOCOL_VERSION_KEY}":"2030-01-01"}}""")
        val error = response["error"]!!.jsonObject
        assertEquals(JsonRpc.UNSUPPORTED_PROTOCOL_VERSION, error["code"]?.jsonPrimitive?.int)
        assertEquals("2026-07-28", error["data"]!!.jsonObject["supported"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `tools list is exactly the three meta tools with strict schemas`() {
        val tools = call("tools/list", "{}")["tools"]!!.jsonArray
        assertEquals(listOf("domains", "tools", "run"), tools.map { it.jsonObject["name"]?.jsonPrimitive?.content })
        for (tool in tools) {
            val schema = tool.jsonObject["inputSchema"]!!.jsonObject
            assertFalse(schema["additionalProperties"]!!.jsonPrimitive.boolean)
            assertTrue("required" in schema)
        }
        assertEquals(call("tools/list", "{}").toString(), call("tools/list", "{}").toString())
    }

    @Test
    fun `domains opens with the TOON primer and one row per domain`() {
        val text = text(callTool("domains", "{}"))
        assertTrue(text.startsWith(MetaTools.PRIMER)) { text }
        assertTrue("domains[2]{name,description}:" in text) { text }
        assertTrue("  read,Files as the IDE sees them" in text) { text }
    }

    @Test
    fun `tools of a domain lists parameters as a table and stays in TOON`() {
        val text = text(callTool("tools", """{"domain":"read"}"""))
        assertTrue("- name: read_file" in text) { text }
        assertTrue("params[1]{name,type,required,description}:" in text) { text }
        assertTrue("path,string,true,Project-relative path" in text) { text }
        assertFalse("{\"" in text) { text }
    }

    @Test
    fun `an unknown domain is a tool error the model can correct`() {
        val result = callTool("tools", """{"domain":"nope"}""")
        assertTrue(result["isError"]!!.jsonPrimitive.boolean)
        assertTrue("read, search" in text(result)) { text(result) }
    }

    @Test
    fun `run dispatches to the inner tool and answers in TOON`() {
        val result = callTool("run", """{"tool":"read_file","args":{"path":"src/A.kt"}}""")
        assertFalse(result["isError"]!!.jsonPrimitive.boolean)
        assertEquals("path: src/A.kt", text(result))
    }

    @Test
    fun `run asks the gate with the whole argument object before executing`() {
        val result = callTool("run", """{"tool":"read_file","args":{"path":"x","nested":{"command":"rm -rf /"}}}""")
        assertTrue(result["isError"]!!.jsonPrimitive.boolean)
        assertEquals("error: denied by the guard", text(result))
        assertEquals("read_file", denied.single()["tool"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a tool exception becomes a tool error, never a protocol error`() {
        val result = callTool("run", """{"tool":"fail"}""")
        assertTrue(result["isError"]!!.jsonPrimitive.boolean)
        assertEquals("error: boom", text(result))
    }

    @Test
    fun `an unknown inner tool is a tool error and an unknown meta tool a protocol error`() {
        assertTrue("unknown tool nope" in text(callTool("run", """{"tool":"nope"}""")))
        val response = send("tools/call", """{"name":"nope","arguments":{}}""")
        assertEquals(JsonRpc.INVALID_PARAMS, response["error"]!!.jsonObject["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `notifications and replies get no answer, junk gets an error`() {
        assertNull(runBlocking { server.handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")) })
        assertNull(runBlocking { server.handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":3,"result":{}}""")) })
        val junk = runBlocking { server.handle(JsonPrimitive("hello")) }!!
        assertEquals(JsonRpc.INVALID_REQUEST, junk["error"]!!.jsonObject["code"]?.jsonPrimitive?.int)
        val unknown = send("nope/nothing", "{}")
        assertEquals(JsonRpc.METHOD_NOT_FOUND, unknown["error"]!!.jsonObject["code"]?.jsonPrimitive?.int)
    }

    private fun modernParams(): String = """{"_meta":{"${McpServer.PROTOCOL_VERSION_KEY}":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}"""

    private fun callTool(name: String, arguments: String): JsonObject = call("tools/call", """{"name":"$name","arguments":$arguments}""")

    private fun text(result: JsonObject): String = result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content

    private fun call(method: String, params: String): JsonObject = send(method, params)["result"]!!.jsonObject

    private fun send(method: String, params: String): JsonObject = runBlocking {
        server.handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}"""))!!
    }
}
