package dev.lain.claudejb.settings

import dev.lain.claudejb.permission.SecurityRule
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SecuritySuspensionsTest {

    private val rule = SecurityRule.DESTRUCTIVE_IAC
    private val other = SecurityRule.DESTRUCTIVE_CLOUD
    private val t0 = 1_700_000_000_000L

    private val SCOPE = "suspensions-test"

    @AfterEach
    fun clearProcessState() {
        SecuritySuspensions.releaseSessionScoped(SCOPE, rule)
        SecuritySuspensions.releaseSessionScoped(SCOPE, other)
    }

    @Test
    fun `a suspension is in force before its instant and gone after it`() {
        val csv = SecuritySuspensions.withSuspension("", rule, 5 * 60_000L, t0)

        assertTrue(rule in SecuritySuspensions.active(csv, t0 + 4 * 60_000L), "four minutes in, still open")
        assertFalse(rule in SecuritySuspensions.active(csv, t0 + 5 * 60_000L), "at the instant it is enforced again")
        assertFalse(rule in SecuritySuspensions.active(csv, t0 + 60 * 60_000L))
    }

    @Test
    fun `expiry needs no write - the same stored value answers differently as time passes`() {
        val csv = SecuritySuspensions.withSuspension("", rule, 30 * 60_000L, t0)

        assertTrue(SecuritySuspensions.active(csv, t0).isNotEmpty())
        assertTrue(SecuritySuspensions.active(csv, t0 + 31 * 60_000L).isEmpty(), "the same CSV, later, opens nothing")
    }

    @Test
    fun `one suspension opens exactly one rule`() {
        val csv = SecuritySuspensions.withSuspension("", rule, 60_000L, t0)

        assertEquals(setOf(rule), SecuritySuspensions.active(csv, t0))
        assertFalse(other in SecuritySuspensions.active(csv, t0), "a rule nobody suspended must stay enforced")
    }

    @Test
    fun `suspending again extends the same rule rather than adding a second entry`() {
        val once = SecuritySuspensions.withSuspension("", rule, 60_000L, t0)
        val twice = SecuritySuspensions.withSuspension(once, rule, 10 * 60_000L, t0)

        assertEquals(1, twice.split(',').size, "one rule, one entry — otherwise the longest wins by accident")
        assertTrue(rule in SecuritySuspensions.active(twice, t0 + 5 * 60_000L))
    }

    @Test
    fun `a write prunes what has already expired`() {
        val stale = SecuritySuspensions.withSuspension("", other, 60_000L, t0)
        val fresh = SecuritySuspensions.withSuspension(stale, rule, 60_000L, t0 + 10 * 60_000L)

        assertFalse(fresh.contains(other.name), "an expired entry is dropped on write, so the document is bounded")
    }

    @Test
    fun `releasing a rule enforces it immediately`() {
        val csv = SecuritySuspensions.withSuspension("", rule, 8 * 60 * 60_000L, t0)

        val released = SecuritySuspensions.without(csv, rule, t0)

        assertTrue(SecuritySuspensions.active(released, t0).isEmpty(), "re-enabling must actually re-enable")
    }

    @Test
    fun `a garbled entry fails safe - it opens nothing`() {
        val csv = "NOT_A_RULE=${t0 + 60_000},${rule.name}=not-a-number,${rule.name},=123"

        assertTrue(SecuritySuspensions.active(csv, t0).isEmpty())
    }

    @Test
    fun `an empty document opens nothing`() {
        assertTrue(SecuritySuspensions.active("", t0).isEmpty())
    }

    @Test
    fun `the longest choice is still bounded`() {
        val csv = SecuritySuspensions.withSuspension("", rule, 8 * 60 * 60_000L, t0)

        assertTrue(rule in SecuritySuspensions.active(csv, t0 + 7 * 60 * 60_000L))
        assertFalse(rule in SecuritySuspensions.active(csv, t0 + 9 * 60 * 60_000L))
    }

    @Test
    fun `until-the-IDE-closes is process state and is never written to the document`() {
        SecuritySuspensions.suspendUntilIdeCloses(SCOPE,rule)

        assertEquals(setOf(rule), SecuritySuspensions.sessionSuspended(SCOPE))
        assertTrue(SecuritySuspensions.active("", t0).isEmpty(), "nothing timed was stored")
    }

    @Test
    fun `enforcing a rule again cancels its process-scoped suspension`() {
        SecuritySuspensions.suspendUntilIdeCloses(SCOPE,rule)
        SecuritySuspensions.suspendUntilIdeCloses(SCOPE,other)

        SecuritySuspensions.releaseSessionScoped(SCOPE,rule)

        assertEquals(setOf(other), SecuritySuspensions.sessionSuspended(SCOPE), "one switch releases one rule")
    }

    @Test
    fun `an unknown duration token is refused rather than defaulted`() {
        assertNull(SecuritySuspensions.Duration.from("7m"))
        assertNull(SecuritySuspensions.Duration.from(""))
        assertNull(SecuritySuspensions.Duration.from(null))
    }

    @Test
    fun `every choice reads as English in the confirming sentence`() {
        SecuritySuspensions.Duration.entries.forEach { d ->
            val sentence = "Destructive IaC is disabled ${d.phrase}."
            assertTrue(d.phrase.isNotBlank(), "${d.name} has no phrase")
            assertFalse(sentence.contains("for forever"), "${d.name} reads badly: $sentence")
            assertFalse(sentence.contains("for until"), "${d.name} reads badly: $sentence")
        }
    }

    @Test
    fun `the two unbounded choices carry no duration, and every other one does`() {
        SecuritySuspensions.Duration.entries.forEach { d ->
            val unbounded = d == SecuritySuspensions.Duration.FOREVER ||
                d == SecuritySuspensions.Duration.UNTIL_IDE_CLOSES
            assertEquals(unbounded, d.millis == null, "${d.name} carries the wrong kind of expiry")
            if (!unbounded) assertTrue(d.millis!! > 0, "${d.name} must be a real bound")
        }
    }

    @Test
    fun `the page offers exactly the durations the host understands`() {
        val js = File("src/main/resources/jcef/app-core.js")
        assertTrue(js.isFile, "CC.GUARD_DURATIONS moved: this contract test has to move with it")
        val tokens = Regex("""\{\s*token:\s*'([^']+)'""").findAll(js.readText()).map { it.groupValues[1] }.toList()

        assertEquals(SecuritySuspensions.Duration.entries.map { it.token }, tokens)
    }
}

class GuardCommandApprovalsTest {

    private val rule = SecurityRule.DESTRUCTIVE_IAC
    private val other = SecurityRule.DESTRUCTIVE_CLOUD

    @Test
    fun `an approved command matches, and only that command`() {
        val approvals = GuardCommandApprovals()
        approvals.approve(rule, "terraform destroy")

        assertTrue(approvals.isApproved(rule, "terraform destroy"))
        assertFalse(approvals.isApproved(rule, "terraform destroy -auto-approve"))
        assertFalse(approvals.isApproved(rule, "terraform apply"))
    }

    @Test
    fun `an approval does not travel to another rule`() {
        val approvals = GuardCommandApprovals()
        approvals.approve(rule, "terraform destroy")

        assertFalse(approvals.isApproved(other, "terraform destroy"))
    }

    @Test
    fun `an approval does not travel to another chat`() {
        val mine = GuardCommandApprovals()
        val theirs = GuardCommandApprovals()
        mine.approve(rule, "terraform destroy")

        assertFalse(theirs.isApproved(rule, "terraform destroy"), "one chat's card must not answer another's")
    }

    @Test
    fun `a blank command is never stored`() {
        val approvals = GuardCommandApprovals()
        approvals.approve(rule, null)
        approvals.approve(rule, "   ")

        assertTrue(approvals.all().isEmpty())
        assertFalse(approvals.isApproved(rule, ""))
        assertFalse(approvals.isApproved(rule, null))
    }

    @Test
    fun `approving twice does not grow the set`() {
        val approvals = GuardCommandApprovals()
        approvals.approve(rule, "kubectl delete ns prod")
        approvals.approve(rule, "kubectl delete ns prod")

        assertEquals(setOf("kubectl delete ns prod"), approvals.all()[rule])
    }

    @Test
    fun `revoking one leaves the rest`() {
        val approvals = GuardCommandApprovals()
        approvals.approve(rule, "terraform destroy")
        approvals.approve(rule, "terraform destroy -target=x")
        approvals.revoke(rule, "terraform destroy")

        assertFalse(approvals.isApproved(rule, "terraform destroy"))
        assertTrue(approvals.isApproved(rule, "terraform destroy -target=x"))
    }
}
