package dev.lain.claudejb.session

import dev.lain.claudejb.permission.PermissionBroker
import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.settings.GuardAlert
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Putting the guard's rows back into a restored conversation.
 *
 * The rows exist nowhere in the binary's transcript — a refusal is a failed tool result with no rule name
 * in it, a bypass is nothing at all — so everything here is about the stitch between that file and the
 * alert log.
 */
class GuardRestoreTest {

    private val rule = SecurityRule.DESTRUCTIVE_IAC

    private fun toolRow(id: String) = EntryDTO(speaker = "TOOL", text = "Bash", toolUseId = id)

    private fun alert(
        verdict: String,
        toolUseId: String? = "tu_1",
        via: String? = null,
        command: String? = "terraform destroy",
    ) = GuardAlert(
        at = 1,
        rule = rule.name,
        category = rule.category.name,
        verdict = verdict,
        sessionId = "s1",
        toolUseId = toolUseId,
        via = via,
        tool = "Bash",
        detail = "runs an irreversible destructive operation",
        command = command,
    )

    @Test
    fun `a conversation with no alerts comes back exactly as it went in`() {
        val dtos = listOf(toolRow("tu_1"), toolRow("tu_2"))

        assertEquals(dtos, GuardRestore.reinstate(dtos, emptyList()))
    }

    @Test
    fun `a block comes back as a block, anchored to the call it refused`() {
        val out = GuardRestore.reinstate(
            listOf(toolRow("tu_0"), toolRow("tu_1"), toolRow("tu_2")),
            listOf(alert(GuardAlert.DENIED)),
        )

        assertEquals(4, out.size)
        assertEquals(rule.name, out[2].blockedRule, "the row goes right after the call, not at the end")
        assertEquals("terraform destroy", out[2].commandText, "or the Whitelist Command link has nothing to add")
        assertTrue(out[2].text.contains("runs an irreversible destructive operation"))
    }

    @Test
    fun `a bypass comes back as a bypass, and says which one it was`() {
        val out = GuardRestore.reinstate(
            listOf(toolRow("tu_1")),
            listOf(alert(GuardAlert.ALLOWED, via = PermissionBroker.ENABLE_GUARD)),
        )

        assertEquals(rule.name, out[1].bypassedRule)
        assertEquals(PermissionBroker.ENABLE_GUARD, out[1].bypassAction)
        assertTrue(out[1].text.contains("the Sensitive Guard is disabled"))
    }

    @Test
    fun `a whitelist bypass can still be taken off the whitelist`() {
        val out = GuardRestore.reinstate(
            listOf(toolRow("tu_1")),
            listOf(alert(GuardAlert.ALLOWED, via = PermissionBroker.REMOVE_FROM_WHITELIST)),
        )

        assertEquals(PermissionBroker.REMOVE_FROM_WHITELIST, out[1].bypassAction)
    }

    @Test
    fun `an Allow All given on a card comes back with no link at all`() {
        val out = GuardRestore.reinstate(
            listOf(toolRow("tu_1")),
            listOf(alert(GuardAlert.ALLOWED, via = PermissionBroker.REVOKE_APPROVAL)),
        )

        assertEquals(rule.name, out[1].bypassedRule, "it still happened, so it is still reported")
        assertNull(
            out[1].bypassAction,
            "the approval lived in memory and died with the IDE: offering to withdraw it would be a lie",
        )
    }

    @Test
    fun `a card that was shown is not a row of its own`() {
        val out = GuardRestore.reinstate(listOf(toolRow("tu_1")), listOf(alert(GuardAlert.ASKED)))

        assertEquals(1, out.size, "however it was answered is its own entry, and that is the row")
    }

    @Test
    fun `an alert whose call fell off the tail is kept, at the end`() {
        val out = GuardRestore.reinstate(
            listOf(toolRow("tu_9")),
            listOf(alert(GuardAlert.DENIED, toolUseId = "tu_gone")),
        )

        assertEquals(2, out.size)
        assertEquals(rule.name, out.last().blockedRule, "out of position beats not there at all")
    }

    @Test
    fun `an alert with no anchor at all is kept too`() {
        val out = GuardRestore.reinstate(listOf(toolRow("tu_1")), listOf(alert(GuardAlert.DENIED, toolUseId = null)))

        assertEquals(rule.name, out.last().blockedRule)
    }

    @Test
    fun `a rule this build no longer has is dropped rather than guessed at`() {
        val stale = alert(GuardAlert.DENIED).copy(rule = "A_RULE_FROM_THE_FUTURE")

        assertEquals(1, GuardRestore.reinstate(listOf(toolRow("tu_1")), listOf(stale)).size)
    }

    @Test
    fun `two alerts on one call both come back, in the order they happened`() {
        val out = GuardRestore.reinstate(
            listOf(toolRow("tu_1")),
            listOf(
                alert(GuardAlert.DENIED),
                alert(GuardAlert.ALLOWED, via = PermissionBroker.ENABLE_GUARD),
            ),
        )

        assertEquals(3, out.size)
        assertEquals(rule.name, out[1].blockedRule)
        assertEquals(rule.name, out[2].bypassedRule)
    }
}
