package dev.lain.claudejb.settings

import dev.lain.claudejb.permission.SecurityRule
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionScopedSuspensionsTest {

    private val scratch = "scratch-project"

    private val work = "work-project"

    private val now = 1_000_000L

    @Test
    fun `a rule relaxed until the IDE closes stays relaxed in that project only`() {
        SecuritySuspensions.suspendUntilIdeCloses(scratch, SecurityRule.CREDENTIALS)

        assertTrue(SecurityRule.CREDENTIALS in SecuritySuspensions.sessionSuspended(scratch))
        assertFalse(
            SecurityRule.CREDENTIALS in SecuritySuspensions.sessionSuspended(work),
            "tuning one repository's rules says nothing about the next one you open",
        )
    }

    @Test
    fun `the whole guard off until the IDE closes does not reach another project`() {
        val scratchState = ClaudeSettings.State()
        val workState = ClaudeSettings.State()

        SecuritySuspensions.guardOff(scratch, scratchState, SecuritySuspensions.Duration.UNTIL_IDE_CLOSES, now)

        assertTrue(SecuritySuspensions.guardSuspended(scratch, scratchState, now))
        assertFalse(
            SecuritySuspensions.guardSuspended(work, workState, now),
            "the master switch is per project, like every other setting",
        )
    }

    @Test
    fun `turning it back on in one project leaves the other as it was`() {
        val a = ClaudeSettings.State()
        val b = ClaudeSettings.State()
        SecuritySuspensions.guardOff("a", a, SecuritySuspensions.Duration.UNTIL_IDE_CLOSES, now)
        SecuritySuspensions.guardOff("b", b, SecuritySuspensions.Duration.UNTIL_IDE_CLOSES, now)

        SecuritySuspensions.guardOn("a", a)

        assertFalse(SecuritySuspensions.guardSuspended("a", a, now))
        assertTrue(SecuritySuspensions.guardSuspended("b", b, now), "b never asked for anything to change")
    }

    @Test
    fun `releasing a session-scoped rule releases it in that project only`() {
        SecuritySuspensions.suspendUntilIdeCloses("x", SecurityRule.PRIVILEGE_ESCALATION)
        SecuritySuspensions.suspendUntilIdeCloses("y", SecurityRule.PRIVILEGE_ESCALATION)

        SecuritySuspensions.releaseSessionScoped("x", SecurityRule.PRIVILEGE_ESCALATION)

        assertFalse(SecurityRule.PRIVILEGE_ESCALATION in SecuritySuspensions.sessionSuspended("x"))
        assertTrue(SecurityRule.PRIVILEGE_ESCALATION in SecuritySuspensions.sessionSuspended("y"))
    }

    @Test
    fun `a timed suspension is persisted state, so it was already per project`() {
        val state = ClaudeSettings.State()
        SecuritySuspensions.guardOff("only-here", state, SecuritySuspensions.Duration.MINUTES_5, now)

        assertTrue(SecuritySuspensions.guardSuspended("only-here", state, now))
        assertFalse(
            SecuritySuspensions.guardSuspended("elsewhere", ClaudeSettings.State(), now),
            "it lives in the document, and each project has its own",
        )
    }
}
