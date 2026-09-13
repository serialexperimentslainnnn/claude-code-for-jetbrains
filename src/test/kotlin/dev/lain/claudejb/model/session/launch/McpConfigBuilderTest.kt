package dev.lain.claudejb.model.session.launch

import dev.lain.claudejb.model.protocol.ClaudeJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class McpConfigBuilderTest {

    private fun parse(s: String): JsonObject =
        ClaudeJson.parseToJsonElement(s).jsonObject

    private fun servers(json: String): JsonObject =
        parse(json)["mcpServers"]!!.jsonObject

    @Test
    fun `no socket and blank custom returns null (no mcp-config flag)`() {
        assertNull(McpConfigBuilder.mcpConfigJson(customMcpServers = ""))
    }

    @Test
    fun `custom servers merge under mcpServers`() {
        val custom = """{"my-srv":{"type":"sse","url":"http://localhost:9000/sse","headers":{}}}"""
        val s = servers(McpConfigBuilder.mcpConfigJson(customMcpServers = custom)!!)
        val my = s["my-srv"]!!.jsonObject
        assertEquals("sse", my["type"]!!.jsonPrimitive.content)
        assertEquals("http://localhost:9000/sse", my["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `invalid custom JSON reports via callback and is dropped (no flag, no crash)`() {
        var captured: Throwable? = null
        val out = McpConfigBuilder.mcpConfigJson(customMcpServers = "{not valid json", onCustomParseError = { captured = it })
        assertNotNull(captured, "parse error must be surfaced through the callback")
        assertNull(out, "an unparseable custom block alone earns no flag")
    }

    @Test
    fun `a non-object custom block is dropped silently beside our own servers`(@TempDir tmp: Path) {
        var captured: Throwable? = null
        val out = McpConfigBuilder.mcpConfigJson(
            customMcpServers = "[]",
            ownSockets = mapOf(IdeServer.CODE to "/run/x/code.sock"),
            helper = helper(tmp),
            onCustomParseError = { captured = it },
        )
        assertNull(captured)
        assertEquals(listOf("code"), servers(out!!).keys.toList())
    }

    @Test
    fun `our own servers are stdio entries, one per socket, launching the helper by a bare java`(@TempDir tmp: Path) {
        val helper = helper(tmp)
        val out = McpConfigBuilder.mcpConfigJson(
            customMcpServers = "",
            ownSockets = mapOf(IdeServer.CODE to "/run/x/code.sock", IdeServer.OPS to "/run/x/ops.sock"),
            helper = helper,
        )
        val s = servers(out!!)
        assertEquals(listOf("code", "ops"), s.keys.toList())
        val code = s["code"]!!.jsonObject
        assertEquals("stdio", code["type"]!!.jsonPrimitive.content)
        assertEquals(helper.javaBin.absolutePath, code["command"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("-cp", helper.lib.absolutePath + File.separator + "*", McpConfigBuilder.HELPER_MAIN, "/run/x/code.sock"),
            code["args"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertNull(code["env"], "the credential never travels by environment")
        assertEquals("/run/x/ops.sock", s["ops"]!!.jsonObject["args"]!!.jsonArray.last().jsonPrimitive.content)
    }

    @Test
    fun `without a helper to launch, a socket earns no entry`() {
        assertNull(McpConfigBuilder.mcpConfigJson(customMcpServers = "", ownSockets = mapOf(IdeServer.CODE to "/run/x/code.sock")))
    }

    @Test
    fun `a custom server named like one of ours wins, and the order is ours first`(@TempDir tmp: Path) {
        val custom = """{"code":{"type":"sse","url":"http://localhost:1/sse","headers":{}},"linter":{"type":"stdio","command":"x"}}"""
        val out = McpConfigBuilder.mcpConfigJson(
            customMcpServers = custom,
            ownSockets = mapOf(IdeServer.CODE to "/run/x/code.sock", IdeServer.VCS to "/run/x/vcs.sock"),
            helper = helper(tmp),
        )
        val s = servers(out!!)
        assertEquals(listOf("code", "vcs", "linter"), s.keys.toList())
        assertEquals("http://localhost:1/sse", s["code"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    private fun helper(tmp: Path): McpConfigBuilder.HelperParams {
        val javaBin = File(tmp.toFile(), "java").apply { writeText("#!/bin/sh\n") }
        val lib = File(tmp.toFile(), "lib").apply { mkdirs() }
        return McpConfigBuilder.HelperParams(javaBin, lib)
    }

    @Test
    fun `customMcpServersObject returns null for blank input`() {
        assertNull(McpConfigBuilder.customMcpServersObject(""))
        assertNull(McpConfigBuilder.customMcpServersObject("   \n"))
    }
}
