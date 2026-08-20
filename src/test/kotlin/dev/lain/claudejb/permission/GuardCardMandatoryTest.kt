package dev.lain.claudejb.permission

import dev.lain.claudejb.protocol.CanUseToolRequest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuardCardMandatoryTest {

    private class Observation {
        var respond: String? = null
        var presented: PendingPermission? = null
        var denied: Denial? = null

        val autoApproved: Boolean get() = respond != null && presented == null
        val manualCard: Boolean get() = presented != null
    }

    private data class Denial(
        val tool: String,
        val reason: String?,
        val rule: SecurityRule?,
        val command: String?,
    )

    private val rule = SecurityRule.DESTRUCTIVE_IAC

    private fun bashReq(cmd: String) = CanUseToolRequest(
        toolName = "Bash",
        input = buildJsonObject { put("command", cmd) },
        toolUseId = "tu_b",
    )

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
            onSensitiveDenied = { tool, reason, r, command -> obs.denied = Denial(tool, reason, r, command) },
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
        listOf("bypassPermissions", "acceptEdits", "plan", "default").forEach { mode ->
            val obs = run(SensitiveGuard.Verdict.ASK, bashReq("terraform destroy"), mode = mode)

            assertTrue(obs.manualCard, "$mode must not answer a guard card")
            assertFalse(obs.autoApproved)
        }
    }

    @Test
    fun `a tool marked always-allow no longer skips a guard card`() {
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
        val obs = run(SensitiveGuard.Verdict.DENY, bashReq("terraform destroy"))

        assertFalse(obs.manualCard, "an enforced rule is refused, not asked about")
        assertNotNull(obs.respond)
        assertEquals(rule, obs.denied?.rule, "the rule must reach the transcript block")
        assertEquals("Bash", obs.denied?.tool)
        assertEquals(
            "terraform destroy",
            obs.denied?.command,
            "the block's Whitelist Command link has nothing to act on without it",
        )
    }

    @Test
    fun `a block with no command to name carries none`() {
        val write = CanUseToolRequest(
            toolName = "Write",
            input = buildJsonObject { put("file_path", "/etc/hosts") },
            toolUseId = "tu_w",
        )

        val obs = run(SensitiveGuard.Verdict.DENY, write)

        assertNull(
            obs.denied?.command,
            "a link offering to whitelist an empty string would be a button that does nothing",
        )
    }

    @Test
    fun `a call with no command text cannot be pre-approved`() {
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
