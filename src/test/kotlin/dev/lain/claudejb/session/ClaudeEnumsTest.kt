package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Locks the `wire` strings of the settings enums: these are persisted in claude-code.xml and sent to the
 * `claude` binary, so a rename must never silently change them. Also guards `from` parsing (unknown → null).
 */
class ClaudeEnumsTest {

    @Test
    fun `permission mode wire values are stable`() {
        assertEquals(
            listOf("default", "acceptEdits", "plan", "bypassPermissions", "dontAsk", "auto"),
            PermissionMode.entries.map { it.wire },
        )
        assertEquals(listOf("default", "acceptEdits", "plan"), PermissionMode.CYCLE.map { it.wire })
    }

    @Test
    fun `effort wire values are stable`() {
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), EffortLevel.entries.map { it.wire })
    }

    @Test
    fun `mcp transport wire values are stable`() {
        assertEquals(listOf("sse", "streamable-http", "stdio"), McpTransport.entries.map { it.wire })
    }

    @Test
    fun `labelFor maps wire modes to human labels and passes through unknowns`() {
        assertEquals("Ask each time", PermissionMode.labelFor("default"))
        assertEquals("Accept edits", PermissionMode.labelFor("acceptEdits"))
        assertEquals("Plan", PermissionMode.labelFor("plan"))
        assertEquals("Bypass permissions", PermissionMode.labelFor("bypassPermissions"))
        // Unknown / null degrade gracefully to the raw value rather than throwing.
        assertEquals("weird", PermissionMode.labelFor("weird"))
        assertEquals("", PermissionMode.labelFor(null))
    }

    @Test
    fun `from resolves wire strings and rejects unknowns`() {
        assertEquals(PermissionMode.BYPASS, PermissionMode.from("bypassPermissions"))
        assertEquals(EffortLevel.MAX, EffortLevel.from("max"))
        assertEquals(McpTransport.STREAMABLE_HTTP, McpTransport.from("streamable-http"))
        assertNull(PermissionMode.from("nope"))
        assertNull(PermissionMode.from(null))
    }
}
