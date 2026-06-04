package dev.lain.claudejb.ui

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure logic of [InfoDialogs]: the `mcp_status` payload parsing and the binary-version / effective-settings
 * formatting. No session, no Swing display — these pin the contract the dialogs render.
 */
class InfoDialogsTest {

    // --- parseMcpServers ---

    @Test
    fun `parses servers under mcp_servers with status and enabled`() {
        val payload = buildJsonObject {
            putJsonArray("mcp_servers") {
                addJsonObject { put("name", "jetbrains"); put("status", "connected"); put("enabled", true) }
                addJsonObject { put("name", "ctx7"); put("status", "failed"); put("enabled", false) }
            }
        }
        val rows = InfoDialogs.parseMcpServers(payload)
        assertEquals(2, rows.size)
        assertEquals(InfoDialogs.McpServerRow("jetbrains", "connected", true), rows[0])
        assertEquals(InfoDialogs.McpServerRow("ctx7", "failed", false), rows[1])
    }

    @Test
    fun `falls back to the servers key and the state field`() {
        val payload = buildJsonObject {
            putJsonArray("servers") {
                addJsonObject { put("name", "alpha"); put("state", "running") }
            }
        }
        val rows = InfoDialogs.parseMcpServers(payload)
        assertEquals(1, rows.size)
        assertEquals("running", rows[0].status)
        assertNull(rows[0].enabled, "enabled absent → null")
    }

    @Test
    fun `unknown status when neither status nor state present`() {
        val payload = buildJsonObject { putJsonArray("mcp_servers") { addJsonObject { put("name", "x") } } }
        assertEquals("unknown", InfoDialogs.parseMcpServers(payload).single().status)
    }

    @Test
    fun `drops entries without a name and tolerates non-objects`() {
        val payload = buildJsonObject {
            putJsonArray("mcp_servers") {
                addJsonObject { put("status", "connected") } // no name → dropped
                addJsonObject { put("name", "  "); put("status", "x") } // blank name → dropped
                add("garbage") // non-object → dropped
                addJsonObject { put("name", "keep") }
            }
        }
        val rows = InfoDialogs.parseMcpServers(payload)
        assertEquals(listOf("keep"), rows.map { it.name })
    }

    @Test
    fun `null or empty payload yields no servers`() {
        assertTrue(InfoDialogs.parseMcpServers(null).isEmpty())
        assertTrue(InfoDialogs.parseMcpServers(buildJsonObject {}).isEmpty())
    }

    // --- formatBinaryVersion ---

    @Test
    fun `formats the version key`() {
        assertEquals("claude 2.1.161", InfoDialogs.formatBinaryVersion(buildJsonObject { put("version", "2.1.161") }))
    }

    @Test
    fun `falls back to binary_version and claude_code_version keys`() {
        assertEquals("claude 9.9", InfoDialogs.formatBinaryVersion(buildJsonObject { put("binary_version", "9.9") }))
        assertEquals("claude 3.0", InfoDialogs.formatBinaryVersion(buildJsonObject { put("claude_code_version", "3.0") }))
    }

    @Test
    fun `binary version placeholder when absent`() {
        assertEquals("Binary version unavailable.", InfoDialogs.formatBinaryVersion(null))
        assertEquals("Binary version unavailable.", InfoDialogs.formatBinaryVersion(buildJsonObject {}))
    }

    // --- formatEffectiveSettings ---

    @Test
    fun `formats top-level settings sorted by key, scalars inline`() {
        val payload = buildJsonObject {
            put("model", "opus")
            put("verbose", true)
        }
        assertEquals("model: opus\nverbose: true", InfoDialogs.formatEffectiveSettings(payload))
    }

    @Test
    fun `unwraps a nested settings object`() {
        val payload = buildJsonObject { putJsonObject("settings") { put("a", "1") } }
        assertEquals("a: 1", InfoDialogs.formatEffectiveSettings(payload))
    }

    @Test
    fun `renders nested objects as compact json`() {
        val payload = buildJsonObject { putJsonObject("env") { put("FOO", "bar") } }
        assertTrue(InfoDialogs.formatEffectiveSettings(payload).startsWith("env: {"))
    }

    @Test
    fun `settings placeholder when empty`() {
        assertEquals("No settings reported.", InfoDialogs.formatEffectiveSettings(null))
        assertEquals("No settings reported.", InfoDialogs.formatEffectiveSettings(buildJsonObject {}))
    }
}

private fun kotlinx.serialization.json.JsonArrayBuilder.addJsonObject(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
    add(buildJsonObject(block))
