package dev.lain.claudejb.settings

import dev.lain.claudejb.permission.SecurityRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
        val s = state {
            disabledSecurityRules = "A_RULE_FROM_THE_FUTURE"
            securityBlockCredentials = false
        }
        LegacySecurityToggles.adopt(s)
        assertTrue("A_RULE_FROM_THE_FUTURE" in s.disabledSecurityRules.split(','))
        assertTrue("CREDENTIALS" in s.disabledSecurityRules.split(','))
    }
}
