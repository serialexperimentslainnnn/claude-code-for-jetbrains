package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.ClaudeJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Verifies the `--mcp-config` JSON [McpConfigBuilder.mcpConfigJson] emits. Compares parsed JSON trees
 * (not literal strings) so the tests are tolerant to whitespace/key-order while still pinning the
 * load-bearing shape the binary consumes (transport types, the synthesized localhost endpoints, the
 * empty `headers` object, and the `jetbrains` key reservation).
 */
class McpConfigBuilderTest {

    private fun parse(s: String): JsonObject =
        ClaudeJson.parseToJsonElement(s).jsonObject

    private fun servers(json: String): JsonObject =
        parse(json)["mcpServers"]!!.jsonObject

    @Test
    fun `disabled IDE plus blank custom returns null (no mcp-config flag)`() {
        val out = McpConfigBuilder.mcpConfigJson(
            ideMcpEnabled = false,
            transport = "sse",
            port = 64342,
            customMcpServers = "",
        )
        assertNull(out)
    }

    @Test
    fun `sse transport synthesizes loopback URL with sse type`() {
        val out = McpConfigBuilder.mcpConfigJson(
            ideMcpEnabled = true, transport = "sse", port = 64342, customMcpServers = "",
        )
        assertNotNull(out)
        val jb = servers(out!!)["jetbrains"]!!.jsonObject
        assertEquals("sse", jb["type"]!!.jsonPrimitive.content)
        assertEquals("http://127.0.0.1:64342/sse", jb["url"]!!.jsonPrimitive.content)
        // Empty headers object is intentional (the binary expects the key).
        assertNotNull(jb["headers"])
        assertTrue(jb["headers"]!!.jsonObject.isEmpty())
    }

    @Test
    fun `streamable-http transport uses stream endpoint and matching type`() {
        val out = McpConfigBuilder.mcpConfigJson(
            ideMcpEnabled = true, transport = "streamable-http", port = 12345, customMcpServers = "",
        )
        val jb = servers(out!!)["jetbrains"]!!.jsonObject
        assertEquals("streamable-http", jb["type"]!!.jsonPrimitive.content)
        assertEquals("http://127.0.0.1:12345/stream", jb["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown transport falls back to sse`() {
        // Implementation: when transport != "stdio" / "streamable-http", defaults to sse.
        val out = McpConfigBuilder.mcpConfigJson(
            ideMcpEnabled = true, transport = "garbage", port = 7777, customMcpServers = "",
        )
        val jb = servers(out!!)["jetbrains"]!!.jsonObject
        assertEquals("sse", jb["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `stdio without StdioParams skips the jetbrains entry`() {
        // jetbrainsMcpServer("stdio", _, null) returns null → the key is omitted; with no custom
        // servers either, the whole config collapses to null (no --mcp-config flag).
        val out = McpConfigBuilder.mcpConfigJson(
            ideMcpEnabled = true, transport = "stdio", port = 1, customMcpServers = "",
            stdioParams = null,
        )
        assertNull(out)
    }

    @Test
    fun `stdio with resolved params emits classpath args and port env`(@TempDir tmp: Path) {
        val javaBin = File(tmp.toFile(), "java").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        val pluginLib = File(tmp.toFile(), "mcpserver/lib").apply { mkdirs() }
        val platformLib = File(tmp.toFile(), "platform/lib").apply { mkdirs() }.absolutePath
        val params = McpConfigBuilder.StdioParams(javaBin, pluginLib, platformLib, port = 4242)

        val out = McpConfigBuilder.mcpConfigJson(
            ideMcpEnabled = true, transport = "stdio", port = 4242, customMcpServers = "",
            stdioParams = params,
        )
        val jb = servers(out!!)["jetbrains"]!!.jsonObject
        assertEquals("stdio", jb["type"]!!.jsonPrimitive.content)
        assertEquals(javaBin.absolutePath, jb["command"]!!.jsonPrimitive.content)
        val args = jb["args"]!!.toString()
        assertTrue(args.contains("-classpath"), "args=$args")
        assertTrue(args.contains("McpStdioRunnerKt"), "args=$args")
        val env = jb["env"]!!.jsonObject
        assertEquals("4242", env["IJ_MCP_SERVER_PORT"]!!.jsonPrimitive.content)
    }

    @Test
    fun `custom server only merges under mcpServers without jetbrains key`() {
        val custom = """{"my-srv":{"type":"sse","url":"http://localhost:9000/sse","headers":{}}}"""
        val out = McpConfigBuilder.mcpConfigJson(
            ideMcpEnabled = false, transport = "sse", port = 0, customMcpServers = custom,
        )
        val s = servers(out!!)
        assertNull(s["jetbrains"], "no jetbrains key when ideMcpEnabled=false")
        val my = s["my-srv"]!!.jsonObject
        assertEquals("sse", my["type"]!!.jsonPrimitive.content)
        assertEquals("http://localhost:9000/sse", my["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `IDE + custom merge without collision (jetbrains key reserved)`() {
        val custom = """{"linter":{"type":"sse","url":"http://localhost:1/sse","headers":{}}}"""
        val out = McpConfigBuilder.mcpConfigJson(
            ideMcpEnabled = true, transport = "sse", port = 64342, customMcpServers = custom,
        )
        val s = servers(out!!)
        assertNotNull(s["jetbrains"])
        assertNotNull(s["linter"])
        assertEquals("http://127.0.0.1:64342/sse", s["jetbrains"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("http://localhost:1/sse", s["linter"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `invalid custom JSON reports via callback and is dropped (no flag, no crash)`() {
        var captured: Throwable? = null
        val out = McpConfigBuilder.mcpConfigJson(
            ideMcpEnabled = false,
            transport = "sse",
            port = 0,
            customMcpServers = "{not valid json",
            onCustomParseError = { captured = it },
        )
        assertNotNull(captured, "parse error must be surfaced through the callback")
        assertNull(out, "with no JetBrains server and an unparseable custom block → no flag emitted")
    }

    @Test
    fun `invalid custom JSON alongside enabled IDE still emits only the jetbrains entry`() {
        var captured: Throwable? = null
        val out = McpConfigBuilder.mcpConfigJson(
            ideMcpEnabled = true,
            transport = "sse",
            port = 64342,
            customMcpServers = "[]", // valid JSON but not an object → treated as null
            onCustomParseError = { captured = it },
        )
        // "[]" is parseable but not a JsonObject, so the cast yields null silently (no error callback).
        assertNull(captured)
        val s = servers(out!!)
        assertNotNull(s["jetbrains"])
        assertEquals(1, s.size, "only the JetBrains entry survives a non-object custom block")
    }

    @Test
    fun `customMcpServersObject returns null for blank input`() {
        assertNull(McpConfigBuilder.customMcpServersObject(""))
        assertNull(McpConfigBuilder.customMcpServersObject("   \n"))
    }
}
