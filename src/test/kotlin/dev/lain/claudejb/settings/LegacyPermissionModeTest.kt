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

/**
 * What a repository is allowed to say about the permission mode — see [LegacyPermissionMode].
 *
 * A committed `.idea/claude-code.xml` used to configure the project that carried it. Since the settings became
 * global it configures every project the user opens next, and the migration that adopts it runs precisely when
 * nothing is stored yet: a fresh install, first project. So the value that decides how often Claude Code asks
 * before touching the machine is the one value that must not arrive that way.
 */
class LegacyPermissionModeTest {

    private val bash = CanUseToolRequest(
        toolName = "Bash",
        input = buildJsonObject { put("command", "ls") },
        toolUseId = "tu_1",
    )

    /** The whole rule in one assertion, stated over the enum rather than over a list kept by hand. */
    @Test
    fun `ask each time and plan are the only modes a legacy file may hand over`() {
        val adoptable = PermissionMode.entries.filterNot { LegacyPermissionMode.weakensSecurity(it.wire) }
        assertEquals(setOf(PermissionMode.DEFAULT, PermissionMode.PLAN), adoptable.toSet())
    }

    /** The reported hole, by name: a hostile repo setting the global mode to bypass. */
    @Test
    fun `bypassPermissions is refused`() {
        assertTrue(LegacyPermissionMode.weakensSecurity("bypassPermissions"))
    }

    /** The other three that stop the user being asked — two of them by the binary, without us ever seeing the call. */
    @Test
    fun `acceptEdits, dontAsk and auto are refused`() {
        assertTrue(LegacyPermissionMode.weakensSecurity("acceptEdits"))
        assertTrue(LegacyPermissionMode.weakensSecurity("dontAsk"))
        assertTrue(LegacyPermissionMode.weakensSecurity("auto"))
    }

    /**
     * An unrecognised string is refused, not passed through.
     *
     * It would reach the binary verbatim as `--permission-mode <it>`, so "this build has never heard of it"
     * says nothing about what it means over there. And refusing costs nothing when the value was harmless:
     * a mis-cased `Default` falls back to `default`, which is what it was trying to say.
     */
    @Test
    fun `an unrecognised mode is refused`() {
        assertTrue(LegacyPermissionMode.weakensSecurity("yolo"))
        assertTrue(LegacyPermissionMode.weakensSecurity("BypassPermissions"))
        assertTrue(LegacyPermissionMode.weakensSecurity("Default"))
    }

    /** No mode at all is not a weakening — it is what an XML without the field looks like. */
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

    /**
     * Ties the classification to the behaviour instead of to an opinion: a mode this file calls safe must not
     * be one under which [PermissionBroker] approves a tool call by itself.
     *
     * The broker is where `acceptEdits`/`bypassPermissions` are actually implemented (the binary always runs in
     * `default`), so this is the real test of "weaker than the default" for the modes the plugin decides. It
     * cannot cover `dontAsk`/`auto`, which the binary settles on its own — those are refused on the stronger
     * ground that a call under them may never reach us at all.
     */
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
