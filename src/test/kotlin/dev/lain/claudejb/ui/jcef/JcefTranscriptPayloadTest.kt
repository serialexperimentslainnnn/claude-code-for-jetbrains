package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.permission.PermissionBroker
import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.session.EntryDTO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JcefTranscriptPayloadTest {

    private val rule = SecurityRule.DESTRUCTIVE_IAC

    private fun rowOf(dto: EntryDTO) = JcefTranscriptPayload.agentRowsJson(listOf(dto)).single()

    @Test
    fun `a refusal inside an agent keeps what its footer is built from`() {
        val row = rowOf(
            EntryDTO(
                speaker = "SYSTEM",
                text = "Blocked Bash: it reaches outside the project.",
                commandText = "ls -l /etc",
                blockedRule = rule.name,
            ),
        )

        assertTrue(row.contains("\"blockedRule\":\"${rule.name}\""), "without the rule there is no Disable rule link")
        assertTrue(row.contains("\"command\":\"ls -l /etc\""), "without the command Whitelist Command has nothing to file")
        assertEquals(
            !rule.whitelistable,
            row.contains("\"blockedRuleWarns\":true"),
            "the warning follows the rule, so a rule that must not be whitelisted still says so in an agent",
        )
    }

    @Test
    fun `a bypass inside an agent still offers the link that undoes it`() {
        val row = rowOf(
            EntryDTO(
                speaker = "SYSTEM",
                text = "Allowed Bash: a bypass is in force.",
                bypassedRule = rule.name,
                bypassAction = PermissionBroker.REMOVE_FROM_WHITELIST,
            ),
        )

        assertTrue(row.contains("\"bypassedRule\":\"${rule.name}\""))
        assertTrue(row.contains("\"bypassAction\":\"${PermissionBroker.REMOVE_FROM_WHITELIST}\""))
    }

    @Test
    fun `an ordinary agent row carries no guard fields at all`() {
        val row = rowOf(EntryDTO(speaker = "TOOL", text = "Bash", meta = "Bash", toolUseId = "tu_1"))

        assertFalse(row.contains("blockedRule"))
        assertFalse(row.contains("bypassedRule"))
    }
}
