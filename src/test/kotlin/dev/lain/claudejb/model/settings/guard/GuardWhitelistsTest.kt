package dev.lain.claudejb.model.settings.guard

import dev.lain.claudejb.model.permission.vocab.SecurityCategory
import dev.lain.claudejb.model.permission.vocab.SecurityRule
import dev.lain.claudejb.model.settings.ClaudeSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuardWhitelistsTest {

    @Test
    fun `the entry a block offers is the program and its subcommand, never the operands`() {
        assertEquals("npm install", GuardWhitelists.entryFor("npm install @scope/package"))
        assertEquals("sudo ls", GuardWhitelists.entryFor("sudo ls /home/someone"))
        assertEquals("git push", GuardWhitelists.entryFor("git push --force origin main"))
        assertEquals("sudo", GuardWhitelists.entryFor("sudo -l"))
        assertEquals("cat", GuardWhitelists.entryFor("cat /etc/passwd"))
        assertEquals("kubectl delete", GuardWhitelists.entryFor("  kubectl   delete namespace prod "))
        assertEquals("", GuardWhitelists.entryFor("   "))
    }

    @Test
    fun `removing a command clears every level that covers it, so nothing above keeps allowing it`() {
        val state = ClaudeSettings.State().apply {
            securityCommandWhitelist = "sudo\nls"
            securityCategoryWhitelists = "SYSTEM_INTEGRITY=sudo ls"
            securityRuleWhitelists = "PRIVILEGE_ESCALATION=sudo ls /home\nPRIVILEGE_ESCALATION=git push"
        }
        val covers = { entry: String -> "sudo ls /home/me".startsWith(entry) }

        val listed = GuardWhitelists.listedIn(state, SecurityRule.PRIVILEGE_ESCALATION, covers)
        assertEquals(setOf(GuardWhitelists.Listed.EVERYWHERE, GuardWhitelists.Listed.CATEGORY, GuardWhitelists.Listed.RULE), listed)

        GuardWhitelists.remove(state, SecurityRule.PRIVILEGE_ESCALATION, listed, covers)
        assertEquals("ls", state.securityCommandWhitelist)
        assertEquals("", state.securityCategoryWhitelists)
        assertEquals("PRIVILEGE_ESCALATION=git push", state.securityRuleWhitelists)
        assertTrue(GuardWhitelists.listedIn(state, SecurityRule.PRIVILEGE_ESCALATION, covers).isEmpty())
    }

    @Test
    fun `the global list is bare commands, comments and blanks dropped`() {
        val text = "# mine\nterraform destroy\n\n  kubectl delete ns dev  "

        assertEquals(listOf("terraform destroy", "kubectl delete ns dev"), GuardWhitelists.commands(text))
    }

    @Test
    fun `a rule list files each command under the rule that names it`() {
        val text = "DESTRUCTIVE_IAC=terraform destroy\nDESTRUCTIVE_GIT=git push --force"

        val byRule = GuardWhitelists.byRule(text)

        assertEquals(setOf("terraform destroy"), byRule[SecurityRule.DESTRUCTIVE_IAC])
        assertEquals(setOf("git push --force"), byRule[SecurityRule.DESTRUCTIVE_GIT])
    }

    @Test
    fun `a command with an equals sign in it survives the round trip`() {
        val text = GuardWhitelists.withEntry("", SecurityRule.CODE_INJECTION.name, "env LD_PRELOAD=/x/y.so ls")

        assertEquals(
            setOf("env LD_PRELOAD=/x/y.so ls"),
            GuardWhitelists.byRule(text)[SecurityRule.CODE_INJECTION],
        )
    }

    @Test
    fun `a category list files each command under its category`() {
        val text = "DESTRUCTIVE_OPERATION=terraform destroy"

        assertEquals(
            setOf("terraform destroy"),
            GuardWhitelists.byCategory(text)[SecurityCategory.DESTRUCTIVE_OPERATION],
        )
    }

    @Test
    fun `a key nobody recognises is dropped rather than guessed at`() {
        assertTrue(GuardWhitelists.byRule("NOT_A_RULE=rm -rf /").isEmpty())
        assertTrue(GuardWhitelists.byCategory("NOT_A_CATEGORY=rm -rf /").isEmpty())
        assertTrue(
            GuardWhitelists.byRule("destructive_iac=terraform destroy").isEmpty(),
            "the lowercase spelling is a different string, and a near-miss must not open anything",
        )
    }

    @Test
    fun `an entry with no command is not an entry`() {
        assertTrue(GuardWhitelists.byRule("DESTRUCTIVE_IAC=").isEmpty())
        assertEquals("", GuardWhitelists.withEntry("", SecurityRule.DESTRUCTIVE_IAC.name, "   "))
    }

    @Test
    fun `adding the same pair twice does not grow the list`() {
        val once = GuardWhitelists.withEntry("", SecurityRule.DESTRUCTIVE_IAC.name, "terraform destroy")
        val twice = GuardWhitelists.withEntry(once, SecurityRule.DESTRUCTIVE_IAC.name, "terraform destroy")

        assertEquals(once, twice)
    }

    @Test
    fun `the same command under two rules is two entries`() {
        val text = GuardWhitelists.withEntry(
            GuardWhitelists.withEntry("", SecurityRule.DESTRUCTIVE_IAC.name, "terraform destroy"),
            SecurityRule.SHELL_FILE_WRITE.name,
            "terraform destroy",
        )

        assertEquals(setOf("terraform destroy"), GuardWhitelists.byRule(text)[SecurityRule.DESTRUCTIVE_IAC])
        assertEquals(setOf("terraform destroy"), GuardWhitelists.byRule(text)[SecurityRule.SHELL_FILE_WRITE])
    }
}
