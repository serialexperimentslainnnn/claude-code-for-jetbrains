package dev.lain.claudejb.permission

import dev.lain.claudejb.protocol.CanUseToolRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Tests the [PermissionBroker]'s decision wiring through its injected lambdas (no process, no UI). The focus is
 * the tool_use id plumbing that keys the persistent edit snapshot: it must reach the [PendingPermission] (manual
 * card) and the [onAutoReviewed] callback (auto-approved edit), and a blank id must degrade to null.
 */
class PermissionBrokerTest {

    private fun broker(
        mode: String,
        present: (PendingPermission) -> Unit = {},
        onAutoReviewed: (String, JsonObject, String) -> Unit = { _, _, _ -> },
        projectRoot: String? = null,
    ) = PermissionBroker(
        permissionMode = { mode },
        respond = {},
        onApprovedWrite = {},
        present = present,
        onAutoReviewed = onAutoReviewed,
        projectRoot = projectRoot,
    )

    @Test
    fun `manual card carries the tool_use id`() {
        var captured: PendingPermission? = null
        val request = CanUseToolRequest(toolName = "Bash", input = buildJsonObject { put("command", "ls") }, toolUseId = "tu_42")

        broker(mode = "default", present = { captured = it }).handle("req-1", request)

        assertEquals("tu_42", captured?.toolUseId)
    }

    @Test
    fun `blank tool_use id degrades to null`() {
        var captured: PendingPermission? = null
        val request = CanUseToolRequest(toolName = "Bash", input = buildJsonObject { put("command", "ls") }, toolUseId = "")

        broker(mode = "default", present = { captured = it }).handle("req-2", request)

        assertNotNull(captured)
        assertNull(captured?.toolUseId)
    }

    @Test
    fun `AskUserQuestion card carries the tool_use id`() {
        var captured: PendingPermission? = null
        val request = CanUseToolRequest(
            toolName = "AskUserQuestion",
            input = buildJsonObject { put("questions", kotlinx.serialization.json.JsonArray(emptyList())) },
            toolUseId = "tu_ask",
        )

        broker(mode = "default", present = { captured = it }).handle("req-3", request)

        assertEquals("tu_ask", captured?.toolUseId)
    }

    @Test
    fun `auto-reviewed edit forwards the tool_use id before the write`(@TempDir dir: Path) {
        val file = File(dir.toFile(), "a.kt")
        var seenId: String? = null
        val request = CanUseToolRequest(
            toolName = "Edit",
            input = buildJsonObject {
                put("file_path", file.path)
                put("old_string", "a")
                put("new_string", "b")
            },
            toolUseId = "tu_edit",
        )

        broker(
            mode = "acceptEdits",
            present = { error("should auto-approve, not present a card") },
            onAutoReviewed = { _, _, id -> seenId = id },
            projectRoot = dir.toFile().path,
        ).handle("req-4", request)

        assertEquals("tu_edit", seenId)
    }

    @Test
    fun `ExitPlanMode surfaces a plan card with the plan text and is never auto-approved`() {
        var captured: PendingPermission? = null
        val request = CanUseToolRequest(
            toolName = "ExitPlanMode",
            input = buildJsonObject { put("plan", "1. do this\n2. then that") },
            toolUseId = "tu_plan",
        )

        // bypassPermissions would auto-approve any normal tool; ExitPlanMode must still be presented.
        broker(mode = "bypassPermissions", present = { captured = it }).handle("req-plan", request)

        assertNotNull(captured)
        assertTrue(captured!!.isPlan)
        assertEquals("1. do this\n2. then that", captured?.planText)
        assertFalse(captured!!.reviewable)
        assertEquals("tu_plan", captured?.toolUseId)
    }

    @Test
    fun `ExitPlanMode without a plan field degrades planText to null`() {
        var captured: PendingPermission? = null
        val request = CanUseToolRequest(toolName = "ExitPlanMode", input = JsonObject(emptyMap()))

        broker(mode = "default", present = { captured = it }).handle("req-plan2", request)

        assertTrue(captured!!.isPlan)
        assertNull(captured?.planText)
    }

    @Test
    fun `rich can_use_tool fields are populated on the manual card`() {
        var captured: PendingPermission? = null
        val request = CanUseToolRequest(
            toolName = "Bash",
            input = buildJsonObject { put("command", "cat /etc/shadow") },
            description = "Read a system file",
            blockedPath = "/etc/shadow",
            decisionReason = "Path outside the project root",
        )

        broker(mode = "default", present = { captured = it }).handle("req-rich", request)

        assertEquals("Read a system file", captured?.description)
        assertEquals("/etc/shadow", captured?.blockedPath)
        assertEquals("Path outside the project root", captured?.decisionReason)
    }

    @Test
    fun `blank rich fields degrade to null`() {
        var captured: PendingPermission? = null
        val request = CanUseToolRequest(
            toolName = "Bash",
            input = buildJsonObject { put("command", "ls") },
            description = "",
            blockedPath = "",
            decisionReason = "",
        )

        broker(mode = "default", present = { captured = it }).handle("req-blank", request)

        assertNull(captured?.description)
        assertNull(captured?.blockedPath)
        assertNull(captured?.decisionReason)
    }

    // ── the guard's two outcomes, as the broker applies them ─────────────────────────────────────────────────
    // An ENFORCED rule is answered without asking anybody; a DISABLED one becomes a card that says so. The card
    // has to carry the RULE, not just a sentence, because that is what the page draws the red alert from and what
    // the user needs in order to find the switch. Until this existed the ASK branch threw the whole decision away.

    private fun guardBroker(
        decision: SensitiveGuard.Decision,
        mode: String = "bypassPermissions",
        present: (PendingPermission) -> Unit = {},
        respond: (String) -> Unit = {},
        remembered: Boolean = false,
        forceAsk: Boolean = false,
        onApprovedWrite: (String) -> Unit = {},
    ) = PermissionBroker(
        permissionMode = { mode },
        respond = respond,
        onApprovedWrite = onApprovedWrite,
        present = present,
        onAutoReviewed = { _, _, _ -> },
        isRemembered = { _, _ -> remembered },
        sensitiveDecision = { decision },
        forceAsk = { forceAsk },
    )

    private val credentialAsk = SensitiveGuard.Decision(
        SensitiveGuard.Verdict.ASK,
        "reads credentials or key material (downgraded to a prompt: disabled in Settings)",
        SecurityRule.CREDENTIALS,
    )

    @Test
    fun `a guard ASK becomes a card that names the rule that tripped`() {
        var captured: PendingPermission? = null
        guardBroker(credentialAsk, present = { captured = it })
            .handle("g-1", CanUseToolRequest(toolName = "Read", input = buildJsonObject { put("file_path", "x") }))

        // The card is tagged as a GUARD card — that flag is the only thing separating this from an ordinary request
        // on screen, and the two are not the same event.
        assertNotNull(captured?.guard)
        assertEquals(SecurityRule.CREDENTIALS, captured?.guard?.rule)
        assertTrue(captured?.guard?.reason!!.contains("credentials or key material"))
    }

    @Test
    fun `an ordinary card carries no guard block, so the page has nothing to decide`() {
        var captured: PendingPermission? = null
        broker(mode = "default", present = { captured = it })
            .handle("p-1", CanUseToolRequest(toolName = "Bash", input = buildJsonObject { put("command", "ls") }))

        assertNotNull(captured)
        assertNull(captured?.guard)
    }

    @Test
    fun `a guard DENY is answered outright — nothing is put to the user, in any mode`() {
        var presented = 0
        val replies = mutableListOf<String>()
        val deny = SensitiveGuard.Decision(SensitiveGuard.Verdict.DENY, "runs a command that can expose secrets", SecurityRule.SECRET_DUMPING_COMMANDS)

        guardBroker(deny, present = { presented++ }, respond = { replies += it })
            .handle("g-2", CanUseToolRequest(toolName = "Bash", input = buildJsonObject { put("command", "x") }))

        assertEquals(0, presented, "an enforced rule must never offer an Accept button")
        assertTrue(replies.single().contains("deny"), replies.single())
    }

    @Test
    fun `Always allow works on a guard card — and it can only ever reach a rule already switched off`() {
        // The ASK branch is unreachable while a rule is enforced (that is a DENY), so honouring "Always allow" here
        // cannot open a lock: at most it stops re-asking about a door the user opened in Settings. It did NOT work
        // before — the guard runs ahead of the auto-approval gate — so the button was on the card and did nothing,
        // which is worse than being absent: the same card came back and the user believed they had answered.
        var presented = 0
        val replies = mutableListOf<String>()
        guardBroker(credentialAsk, present = { presented++ }, respond = { replies += it }, remembered = true)
            .handle("g-3", CanUseToolRequest(toolName = "Read", input = buildJsonObject { put("file_path", "x") }))

        assertEquals(0, presented)
        assertTrue(replies.single().contains("allow"), replies.single())
    }

    @Test
    fun `forceAsk still wins over Always allow on a guard card`() {
        // The Git conversation's turns are started by a button in the IDE, so every one of its calls is put to the
        // user whatever they have remembered about their own work.
        var captured: PendingPermission? = null
        guardBroker(credentialAsk, present = { captured = it }, remembered = true, forceAsk = true)
            .handle("g-4", CanUseToolRequest(toolName = "Read", input = buildJsonObject { put("file_path", "x") }))

        assertNotNull(captured?.guard)
    }

    @Test
    fun `a remembered reviewable write outside the project root is still a card, not an auto-approval`() {
        // The containment rule survives this path too: "Always allow" widens what may run unasked INSIDE the
        // project, never what may be written outside it.
        var captured: PendingPermission? = null
        var wrote: String? = null
        val outside = buildJsonObject { put("file_path", "/elsewhere/App.kt") }
        PermissionBroker(
            permissionMode = { "bypassPermissions" },
            respond = {},
            onApprovedWrite = { wrote = it },
            present = { captured = it },
            onAutoReviewed = { _, _, _ -> },
            isRemembered = { _, _ -> true },
            projectRoot = "/home/me/proj",
            sensitiveDecision = { credentialAsk },
        ).handle("g-5", CanUseToolRequest(toolName = "Edit", input = outside))

        assertNull(wrote)
        assertNotNull(captured?.guard)
    }
}
