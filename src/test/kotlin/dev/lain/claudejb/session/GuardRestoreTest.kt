package dev.lain.claudejb.session

import dev.lain.claudejb.permission.PermissionBroker
import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.settings.GuardAlert
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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

    private fun stampedRow(id: String, at: Long) =
        EntryDTO(speaker = "TOOL", text = "Bash", toolUseId = id, atMillis = at)

    private fun stampedAlert(at: Long) = alert(GuardAlert.DENIED, toolUseId = "gone").copy(at = at)

    @Test
    fun `an alert whose call fell off the tail lands where it happened, not at the end`() {
        val out = GuardRestore.reinstate(
            listOf(stampedRow("tu_1", at = 100), stampedRow("tu_2", at = 300)),
            listOf(stampedAlert(at = 200)),
        )

        assertEquals(3, out.size)
        assertEquals("tu_1", out[0].toolUseId)
        assertEquals(rule.name, out[1].blockedRule, "it belongs between the two calls it happened between")
        assertEquals("tu_2", out[2].toolUseId)
    }

    @Test
    fun `several homeless alerts keep the order they happened in`() {
        val out = GuardRestore.reinstate(
            listOf(stampedRow("tu_1", at = 100), stampedRow("tu_2", at = 400)),
            listOf(stampedAlert(at = 300), stampedAlert(at = 200)),
        )

        assertEquals(listOf(null, rule.name, rule.name, null), out.map { it.blockedRule })
    }

    @Test
    fun `an alert later than everything restored still comes last`() {
        val out = GuardRestore.reinstate(
            listOf(stampedRow("tu_1", at = 100)),
            listOf(stampedAlert(at = 900)),
        )

        assertEquals(rule.name, out.last().blockedRule)
    }

    @Test
    fun `an alert older than everything restored is left to the guard log, never piled at the end`() {
        val out = GuardRestore.reinstate(
            listOf(stampedRow("tu_1", at = 500), stampedRow("tu_2", at = 600)),
            listOf(stampedAlert(at = 10), stampedAlert(at = 550)),
        )

        assertEquals(3, out.size, "the guard log keeps more than the transcript does: the excess is not a tail dump")
        assertEquals(rule.name, out[1].blockedRule, "the one inside the window still lands where it happened")
        assertNull(out.last().blockedRule)
    }

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
    fun `an alert with nowhere to go is left to the guard log, not dumped at the end`() {
        val out = GuardRestore.reinstate(
            listOf(toolRow("tu_9")),
            listOf(alert(GuardAlert.DENIED, toolUseId = "tu_gone")),
        )

        assertEquals(1, out.size, "a transcript with no timestamps cannot say where this belongs")
        assertNull(out.last().blockedRule)
    }

    @Test
    fun `an alert older than this release, with no time of its own, is not restored`() {
        val out = GuardRestore.reinstate(
            listOf(stampedRow("tu_1", at = 100)),
            listOf(alert(GuardAlert.DENIED, toolUseId = null).copy(at = 0)),
        )

        assertEquals(1, out.size)
        assertNull(out.last().blockedRule)
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
