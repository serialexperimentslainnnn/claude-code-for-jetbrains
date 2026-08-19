package dev.lain.claudejb.settings

import dev.lain.claudejb.permission.SecurityRule
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The two stores behind *Disable rule* on a guard block, and the property that makes offering it defensible:
 * **every one of them can only ever open a rule for a bounded time, and none of them can allow anything.**
 *
 * A suspension downgrades a rule to ASK — the card is still mandatory — so what these assert is the arithmetic
 * of the bound and the direction each failure falls in. The clock is a parameter throughout: a test that waited
 * five real minutes to check a five-minute suspension is a test nobody runs.
 */
class SecuritySuspensionsTest {

    private val rule = SecurityRule.DESTRUCTIVE_IAC
    private val other = SecurityRule.DESTRUCTIVE_CLOUD
    private val t0 = 1_700_000_000_000L

    /** The process-scoped store outlives a test method, so each one hands back what it took. */
    @AfterEach
    fun clearProcessState() {
        SecuritySuspensions.releaseSessionScoped(rule)
        SecuritySuspensions.releaseSessionScoped(other)
    }

    // ── the timed store ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a suspension is in force before its instant and gone after it`() {
        val csv = SecuritySuspensions.withSuspension("", rule, 5 * 60_000L, t0)

        assertTrue(rule in SecuritySuspensions.active(csv, t0 + 4 * 60_000L), "four minutes in, still open")
        assertFalse(rule in SecuritySuspensions.active(csv, t0 + 5 * 60_000L), "at the instant it is enforced again")
        assertFalse(rule in SecuritySuspensions.active(csv, t0 + 60 * 60_000L))
    }

    @Test
    fun `expiry needs no write - the same stored value answers differently as time passes`() {
        // The whole reason a suspension heals itself: nothing has to run, be remembered, or be cleaned up. The
        // guard recomputes the open set on every call, so the rule is enforced again on the very next one.
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
        // The direction of every failure in this file. A stale rule name, a missing instant, a value that is not
        // a number: each can only ever fail to OPEN a rule, which costs a card and never a credential.
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

    // ── the process-scoped store ────────────────────────────────────────────────────────────────────────

    @Test
    fun `until-the-IDE-closes is process state and is never written to the document`() {
        SecuritySuspensions.suspendUntilIdeCloses(rule)

        assertEquals(setOf(rule), SecuritySuspensions.sessionSuspended())
        // The IDE going away IS the expiry, so persisting it would let it outlive its own meaning.
        assertTrue(SecuritySuspensions.active("", t0).isEmpty(), "nothing timed was stored")
    }

    @Test
    fun `enforcing a rule again cancels its process-scoped suspension`() {
        SecuritySuspensions.suspendUntilIdeCloses(rule)
        SecuritySuspensions.suspendUntilIdeCloses(other)

        SecuritySuspensions.releaseSessionScoped(rule)

        assertEquals(setOf(other), SecuritySuspensions.sessionSuspended(), "one switch releases one rule")
    }

    // ── the duration vocabulary ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `an unknown duration token is refused rather than defaulted`() {
        // A guessed default here would be a security rule opened by a message nobody authored.
        assertNull(SecuritySuspensions.Duration.from("7m"))
        assertNull(SecuritySuspensions.Duration.from(""))
        assertNull(SecuritySuspensions.Duration.from(null))
    }

    @Test
    fun `every choice reads as English in the confirming sentence`() {
        // The row the user gets after clicking is the only confirmation there is, so a choice whose phrase was
        // derived from its menu label ("disabled for forever") would ship a broken sentence on a security control.
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

    /**
     * The menu the user actually clicks is built in JavaScript, so the two lists have to agree, and review is
     * not a mechanism. A token the page offers and the host does not know would render a menu entry that does
     * nothing — the "dead control" failure this repository has shipped before.
     */
    @Test
    fun `the page offers exactly the durations the host understands`() {
        val js = File("src/main/resources/jcef/app-transcript-rows.js")
        assertTrue(js.isFile, "the row builders moved: this contract test has to move with them")
        val tokens = Regex("""\{\s*token:\s*'([^']+)'""").findAll(js.readText()).map { it.groupValues[1] }.toList()

        assertEquals(SecuritySuspensions.Duration.entries.map { it.token }, tokens)
    }
}

/**
 * "Always allow" on a guard card, and the one distinction that keeps it from being a hole: it remembers a
 * COMMAND, filed under the rule that stopped it — never a tool, and never the rule itself.
 */
class SecurityCommandApprovalsTest {

    private val rule = SecurityRule.DESTRUCTIVE_IAC
    private val other = SecurityRule.DESTRUCTIVE_CLOUD

    @Test
    fun `an approved command matches, and only that command`() {
        val lines = SecurityCommandApprovals.withApproval("", rule, "terraform destroy")

        assertTrue(SecurityCommandApprovals.isApproved(lines, rule, "terraform destroy"))
        // THE property. One click on a `terraform destroy` card must not open everything else the same rule
        // stops — which is exactly what a tool-level answer ("Bash") would have done.
        assertFalse(SecurityCommandApprovals.isApproved(lines, rule, "terraform destroy -auto-approve"))
        assertFalse(SecurityCommandApprovals.isApproved(lines, rule, "terraform apply"))
    }

    @Test
    fun `an approval does not travel to another rule`() {
        val lines = SecurityCommandApprovals.withApproval("", rule, "terraform destroy")

        assertFalse(SecurityCommandApprovals.isApproved(lines, other, "terraform destroy"))
    }

    @Test
    fun `a blank command is never stored`() {
        // A call with no command text has nothing exact to remember, and an empty entry would match every such
        // call under that rule — the tool-wide bypass this design exists to avoid.
        assertEquals("", SecurityCommandApprovals.withApproval("", rule, null))
        assertEquals("", SecurityCommandApprovals.withApproval("", rule, "   "))
        assertFalse(SecurityCommandApprovals.isApproved("${rule.name}=", rule, ""))
        assertFalse(SecurityCommandApprovals.isApproved("${rule.name}=", rule, null))
    }

    @Test
    fun `approving twice does not grow the document`() {
        val once = SecurityCommandApprovals.withApproval("", rule, "kubectl delete ns prod")
        val twice = SecurityCommandApprovals.withApproval(once, rule, "kubectl delete ns prod")

        assertEquals(once, twice)
    }

    @Test
    fun `a stale rule name is dropped rather than guessed`() {
        assertFalse(SecurityCommandApprovals.isApproved("NOT_A_RULE=terraform destroy", rule, "terraform destroy"))
    }

    @Test
    fun `several approvals coexist under one rule`() {
        val lines = SecurityCommandApprovals.withApproval(
            SecurityCommandApprovals.withApproval("", rule, "terraform destroy"),
            rule,
            "terraform destroy -target=x",
        )

        assertTrue(SecurityCommandApprovals.isApproved(lines, rule, "terraform destroy"))
        assertTrue(SecurityCommandApprovals.isApproved(lines, rule, "terraform destroy -target=x"))
    }
}
