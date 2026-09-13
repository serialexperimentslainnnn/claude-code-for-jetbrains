package dev.lain.claudejb.model.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ClaudeEnumsTest {

    @Test
    fun `permission mode wire values are stable`() {
        assertEquals(
            listOf("default", "acceptEdits", "plan", "bypassPermissions", "dontAsk", "auto"),
            PermissionMode.entries.map { it.wire },
        )
    }

    @Test
    fun `effort wire values are stable`() {
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), EffortLevel.entries.map { it.wire })
    }

    @Test
    fun `labelFor maps wire modes to human labels and passes through unknowns`() {
        assertEquals("Ask each time", PermissionMode.labelFor("default"))
        assertEquals("Accept edits", PermissionMode.labelFor("acceptEdits"))
        assertEquals("Plan", PermissionMode.labelFor("plan"))
        assertEquals("Bypass permissions", PermissionMode.labelFor("bypassPermissions"))
        assertEquals("weird", PermissionMode.labelFor("weird"))
        assertEquals("", PermissionMode.labelFor(null))
    }

    @Test
    fun `from resolves wire strings and rejects unknowns`() {
        assertEquals(PermissionMode.BYPASS, PermissionMode.from("bypassPermissions"))
        assertEquals(EffortLevel.MAX, EffortLevel.from("max"))
        assertNull(PermissionMode.from("nope"))
        assertNull(PermissionMode.from(null))
    }
}
