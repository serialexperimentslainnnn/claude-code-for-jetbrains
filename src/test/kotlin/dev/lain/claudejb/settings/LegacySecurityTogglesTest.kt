package dev.lain.claudejb.settings

import dev.lain.claudejb.permission.SecurityRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The one-release fold that turns the seven `securityBlock*` booleans into `disabledSecurityRules`.
 *
 * It is worth its own test for one reason: [SettingsStore] is versionless and field-agnostic, so getting this
 * wrong does not fail — it silently re-enables a rule the user deliberately switched off, which is the one class
 * of settings bug nobody reports because it looks like the plugin working.
 */
class LegacySecurityTogglesTest {

    private fun state(block: ClaudeSettings.State.() -> Unit = {}) = ClaudeSettings.State().apply(block)

    @Test
    fun `a default document is left completely alone`() {
        val s = state()
        LegacySecurityToggles.adopt(s)
        assertEquals("", s.disabledSecurityRules)
    }

    @Test
    fun `a boolean switched off becomes its rule, and the boolean is retired`() {
        val s = state { securityBlockTempDirs = false }
        LegacySecurityToggles.adopt(s)
        assertEquals(SecurityRule.TEMP_DIR.name, s.disabledSecurityRules)
        // Retired, so nothing reads it as configuration again — and so the fold is a no-op from here on.
        assertTrue(s.securityBlockTempDirs)
    }

    @Test
    fun `all seven map to the rules they meant`() {
        val s = state {
            securityBlockCredentials = false
            securityBlockDangerousCommands = false
            securityBlockTempDirs = false
            securityBlockForeignOtherUserHome = false
            securityBlockForeignNetworkMounts = false
            securityBlockForeignWslMounts = false
            securityBlockOutsideProject = false
        }
        LegacySecurityToggles.adopt(s)
        assertEquals(
            setOf(
                SecurityRule.CREDENTIALS,
                SecurityRule.SECRET_DUMPING_COMMANDS,
                SecurityRule.TEMP_DIR,
                SecurityRule.OTHER_USER_HOME,
                SecurityRule.NETWORK_MOUNT,
                SecurityRule.WSL_MOUNT,
                SecurityRule.OUTSIDE_PROJECT,
            ).map { it.name }.toSet(),
            s.disabledSecurityRules.split(',').toSet(),
        )
        // …and the rules that did not exist as a boolean are NOT disabled by the migration.
        listOf(
            SecurityRule.SHELL_FILE_WRITE,
            SecurityRule.SYSTEM_DEVICE,
            SecurityRule.PROXY_BYPASS,
            SecurityRule.BLOCKED_DOMAIN,
            SecurityRule.SCRIPT_EXECUTION,
            SecurityRule.UNRESOLVED_VARIABLE,
            SecurityRule.RECURSION_LIMIT,
        ).forEach { assertTrue(it.name !in s.disabledSecurityRules.split(','), it.name) }
    }

    @Test
    fun `it is idempotent, because ClaudeSettings applies its blocks twice by design`() {
        val s = state { securityBlockCredentials = false }
        LegacySecurityToggles.adopt(s)
        val once = s.disabledSecurityRules
        LegacySecurityToggles.adopt(s)
        LegacySecurityToggles.adopt(s)
        assertEquals(once, s.disabledSecurityRules)
    }

    @Test
    fun `an existing entry is kept, and never duplicated`() {
        val s = state {
            disabledSecurityRules = "TEMP_DIR"
            securityBlockTempDirs = false
            securityBlockCredentials = false
        }
        LegacySecurityToggles.adopt(s)
        assertEquals(listOf("TEMP_DIR", "CREDENTIALS"), s.disabledSecurityRules.split(','))
    }

    @Test
    fun `an id from a NEWER version is preserved, not pruned`() {
        // Pruning it here would re-enable, on the next save, a rule the user turned off in a later IDE.
        val s = state {
            disabledSecurityRules = "A_RULE_FROM_THE_FUTURE"
            securityBlockCredentials = false
        }
        LegacySecurityToggles.adopt(s)
        assertTrue("A_RULE_FROM_THE_FUTURE" in s.disabledSecurityRules.split(','))
        assertTrue("CREDENTIALS" in s.disabledSecurityRules.split(','))
    }
}
