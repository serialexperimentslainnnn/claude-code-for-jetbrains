package dev.lain.claudejb.controller.github

import dev.lain.claudejb.model.mcp.ToolException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class MarketplaceGatewayTest {

    private val reply =
        """[{"version":"6.0.0","channel":"","listed":true,"approve":true,"cdate":"1757707200000","sinceUntil":"253.29346.138 — 263.*"},""" +
            """{"version":"5.8.1","channel":"eap","listed":false,"approve":false}]"""

    @Test
    fun `the updates are read from the plugin's endpoint, newest first, with the defaults filled in`() {
        val asked = ArrayList<String>()
        val updates = MarketplaceGateway.updates(31965, 2) { url ->
            asked += url
            reply
        }
        assertEquals(listOf("https://plugins.jetbrains.com/api/plugins/31965/updates?size=2"), asked)
        assertEquals(listOf("6.0.0", "5.8.1"), updates.map { it.version })
        assertEquals("stable", updates[0].channel)
        assertTrue(updates[0].listed && updates[0].approved)
        assertEquals("2025-09-12T20:00:00Z", updates[0].publishedAt)
        assertEquals("253.29346.138 — 263.*", updates[0].range)
        assertEquals("eap", updates[1].channel)
        assertFalse(updates[1].listed || updates[1].approved)
        assertEquals("", updates[1].publishedAt)
    }

    @Test
    fun `a marketplace that does not answer, or answers something else, is a named refusal`() {
        val down = assertThrows(ToolException::class.java) { MarketplaceGateway.updates(1, 1) { throw IOException("timed out") } }
        assertTrue(down.message!!.contains("timed out"))
        val odd = assertThrows(ToolException::class.java) { MarketplaceGateway.updates(1, 1) { "{\"error\":\"nope\"}" } }
        assertTrue(odd.message!!.contains("not an update list"))
    }
}
