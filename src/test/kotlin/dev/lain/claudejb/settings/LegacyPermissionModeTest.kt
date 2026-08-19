package dev.lain.claudejb.settings

import dev.lain.claudejb.permission.PermissionBroker
import dev.lain.claudejb.protocol.CanUseToolRequest
import dev.lain.claudejb.session.PermissionMode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LegacyPermissionModeTest {

    private val bash = CanUseToolRequest(
        toolName = "Bash",
        input = buildJsonObject { put("command", "ls") },
        toolUseId = "tu_1",
    )

    @Test
    fun `ask each time and plan are the only modes a legacy file may hand over`() {
        val adoptable = PermissionMode.entries.filterNot { LegacyPermissionMode.weakensSecurity(it.wire) }
        assertEquals(setOf(PermissionMode.DEFAULT, PermissionMode.PLAN), adoptable.toSet())
    }

    @Test
    fun `bypassPermissions is refused`() {
        assertTrue(LegacyPermissionMode.weakensSecurity("bypassPermissions"))
    }

    @Test
    fun `acceptEdits, dontAsk and auto are refused`() {
        assertTrue(LegacyPermissionMode.weakensSecurity("acceptEdits"))
        assertTrue(LegacyPermissionMode.weakensSecurity("dontAsk"))
        assertTrue(LegacyPermissionMode.weakensSecurity("auto"))
    }

    @Test
    fun `an unrecognised mode is refused`() {
        assertTrue(LegacyPermissionMode.weakensSecurity("yolo"))
        assertTrue(LegacyPermissionMode.weakensSecurity("BypassPermissions"))
        assertTrue(LegacyPermissionMode.weakensSecurity("Default"))
    }

    @Test
    fun `an absent mode is adopted quietly`() {
        assertFalse(LegacyPermissionMode.weakensSecurity(null))
        assertFalse(LegacyPermissionMode.weakensSecurity(""))
        assertFalse(LegacyPermissionMode.weakensSecurity("   "))
    }

    @Test
    fun `the fallback is exactly the fresh-install default`() {
        assertEquals(PermissionMode.DEFAULT.wire, LegacyPermissionMode.SAFE)
        assertEquals(ClaudeSettings.State().permissionMode, LegacyPermissionMode.SAFE)
        assertFalse(LegacyPermissionMode.weakensSecurity(LegacyPermissionMode.SAFE))
    }

    @Test
    fun `a mode classified safe never unlocks the broker's auto-approval`() {
        PermissionMode.entries.filterNot { LegacyPermissionMode.weakensSecurity(it.wire) }.forEach { mode ->
            var approved = false
            var carded = false
            PermissionBroker(
                permissionMode = { mode.wire },
                respond = { approved = true },
                onApprovedWrite = {},
                present = { carded = true },
                onAutoReviewed = { _, _, _ -> },
            ).handle("req-1", bash)

            assertFalse(approved, "${mode.wire} auto-approves: it must not be adoptable from a repository")
            assertTrue(carded, "${mode.wire} produced no permission card")
        }
    }
}
