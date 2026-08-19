package dev.lain.claudejb.permission

import dev.lain.claudejb.protocol.CanUseToolRequest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **Opening a security rule buys a QUESTION, never a pass** — the property the *Disable rule* control on a
 * guard block rests on, asserted at the one place that can betray it.
 *
 * A rule the user disabled or suspended is downgraded to `ASK`, and the whole design of offering that from a
 * block depends on nothing being able to answer the resulting card implicitly. Two things used to be able to:
 * the permission mode (never, and that was already true) and the tool-level "Always allow" (which did, and was
 * the last implicit pass — one click on a `Bash` card opened every command `Bash` can run, including every other
 * one the same rule exists to stop).
 *
 * The single exception is an answer the user gave ON a card of exactly this kind, about exactly this command.
 * These tests pin both halves: that the exception is honoured, and that it cannot generalise past the command
 * it was given for.
 */
class GuardCardMandatoryTest {

    private class Observation {
        var respond: String? = null
        var presented: PendingPermission? = null
        var denied: Triple<String, String?, SecurityRule?>? = null

        val autoApproved: Boolean get() = respond != null && presented == null
        val manualCard: Boolean get() = presented != null
    }

    private val rule = SecurityRule.DESTRUCTIVE_IAC

    private fun bashReq(cmd: String) = CanUseToolRequest(
        toolName = "Bash",
        input = buildJsonObject { put("command", cmd) },
        toolUseId = "tu_b",
    )

    /**
     * Drives the broker with the guard's verdict supplied directly, so what is under test is the BROKER's
     * policy and not the rule set that produced the verdict. The detection rules have their own suites.
     */
    private fun run(
        verdict: SensitiveGuard.Verdict,
        request: CanUseToolRequest,
        mode: String = "default",
        alwaysAllowedTools: Set<String> = emptySet(),
        approvedCommands: Set<Pair<SecurityRule, String>> = emptySet(),
    ): Observation {
        val obs = Observation()
        val broker = PermissionBroker(
            permissionMode = { mode },
            respond = { obs.respond = it },
            onApprovedWrite = {},
            present = { obs.presented = it },
            onAutoReviewed = { _, _, _ -> },
            isRemembered = { tool, _ -> tool in alwaysAllowedTools },
            projectRoot = null,
            sensitiveDecision = { SensitiveGuard.Decision(verdict, "runs a destructive command", rule) },
            onSensitiveDenied = { tool, reason, r -> obs.denied = Triple(tool, reason, r) },
            isGuardCommandApproved = { r, command ->
                approvedCommands.any { it.first == r && it.second == command }
            },
        )
        broker.handle("req-guard", request)
        return obs
    }

    @Test
    fun `a disabled rule always produces a card`() {
        val obs = run(SensitiveGuard.Verdict.ASK, bashReq("terraform destroy"))

        assertTrue(obs.manualCard, "disabled means ASK: the user answers, every time")
        assertEquals(rule, obs.presented?.guard?.rule, "the card must name the rule that fired")
    }

    @Test
    fun `no permission mode can answer a guard card`() {
        // Already true, and pinned here because it is half of "disabled is not a bypass": `bypassPermissions`
        // means "stop asking about my ordinary work", never "stop watching for this".
        listOf("bypassPermissions", "acceptEdits", "plan", "default").forEach { mode ->
            val obs = run(SensitiveGuard.Verdict.ASK, bashReq("terraform destroy"), mode = mode)

            assertTrue(obs.manualCard, "$mode must not answer a guard card")
            assertFalse(obs.autoApproved)
        }
    }

    @Test
    fun `a tool marked always-allow no longer skips a guard card`() {
        // THE CHANGE. This used to auto-approve: `Bash` was remembered, so the guard stopped asking about every
        // command `Bash` can run. The unit of that answer was wrong, not its existence — see the next test.
        val obs = run(
            SensitiveGuard.Verdict.ASK,
            bashReq("terraform destroy"),
            alwaysAllowedTools = setOf("Bash"),
        )

        assertTrue(obs.manualCard, "a tool-wide answer must not open a rule")
        assertFalse(obs.autoApproved)
    }

    @Test
    fun `the exact command the user approved on a card of this kind does pass`() {
        val obs = run(
            SensitiveGuard.Verdict.ASK,
            bashReq("terraform destroy"),
            approvedCommands = setOf(rule to "terraform destroy"),
        )

        assertTrue(obs.autoApproved, "an explicit per-command answer is the one thing that may skip the card")
    }

    @Test
    fun `an approval does not stretch to a neighbouring command`() {
        // What makes the exception safe: it authorises the command the user READ on the card, not the family it
        // belongs to. Anything adjacent asks again.
        listOf("terraform destroy -auto-approve", "terraform destroy -target=prod", "terraform apply").forEach { cmd ->
            val obs = run(
                SensitiveGuard.Verdict.ASK,
                bashReq(cmd),
                approvedCommands = setOf(rule to "terraform destroy"),
            )

            assertTrue(obs.manualCard, "\"$cmd\" is not the command that was approved")
        }
    }

    @Test
    fun `an approval given under another rule does not answer this one`() {
        val obs = run(
            SensitiveGuard.Verdict.ASK,
            bashReq("terraform destroy"),
            approvedCommands = setOf(SecurityRule.DESTRUCTIVE_CLOUD to "terraform destroy"),
        )

        assertTrue(obs.manualCard, "the approval is filed under the rule that stopped the call")
    }

    @Test
    fun `an enforced rule still denies, and the block carries the rule that refused`() {
        // The block in the transcript is where *Disable rule* lives, and a link has to know which rule it opens.
        val obs = run(SensitiveGuard.Verdict.DENY, bashReq("terraform destroy"))

        assertFalse(obs.manualCard, "an enforced rule is refused, not asked about")
        assertNotNull(obs.respond)
        assertEquals(rule, obs.denied?.third, "the rule must reach the transcript block")
        assertEquals("Bash", obs.denied?.first)
    }

    @Test
    fun `a call with no command text cannot be pre-approved`() {
        // There is nothing exact to remember, so the conjunction simply never holds — a file write under a rule
        // the user opened keeps asking, which is the fail-safe direction.
        val write = CanUseToolRequest(
            toolName = "Write",
            input = buildJsonObject {
                put("file_path", "/tmp/x")
                put("content", "hello")
            },
            toolUseId = "tu_w",
        )

        val obs = run(SensitiveGuard.Verdict.ASK, write, approvedCommands = setOf(rule to ""))

        assertTrue(obs.manualCard)
    }
}
