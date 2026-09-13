package dev.lain.claudejb.view.payload.chat

import dev.lain.claudejb.model.permission.broker.PermissionBroker
import dev.lain.claudejb.model.permission.vocab.SecurityRule
import dev.lain.claudejb.model.session.transcript.EntryDTO
import dev.lain.claudejb.model.session.transcript.Speaker
import dev.lain.claudejb.model.session.transcript.TranscriptEntry
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

    @Test
    fun `every tool card starts collapsed, an own one included, so only its args line shows until clicked`() {
        val own = TranscriptEntry(1, Speaker.TOOL, "code ▸ search_text", meta = "mcp__code__run", toolUseId = "tu_own")
        val bash = TranscriptEntry(2, Speaker.TOOL, "Bash(ls)", meta = "Bash", toolUseId = "tu_bash")
        val output = TranscriptEntry(3, Speaker.TOOL_OUTPUT, "rows", meta = "mcp__code__run", toolUseId = "tu_own")

        assertFalse(JcefTranscriptPayload.entryJson(own, 0).toString().contains("\"open\""))
        assertFalse(JcefTranscriptPayload.entryJson(bash, 0).toString().contains("\"open\""))
        assertFalse(JcefTranscriptPayload.entryJson(output, 1).toString().contains("\"open\""))
    }

    @Test
    fun `a card is reviewable when its entry says so, whatever tool it names`() {
        val own = TranscriptEntry(1, Speaker.TOOL, "code ▸ replace_text ▸ A.kt", meta = "mcp__code__run", toolUseId = "tu_1", reviewable = true)
        val edit = TranscriptEntry(2, Speaker.TOOL, "Edit(A.kt)", meta = "Edit", toolUseId = "tu_2")

        assertTrue(JcefTranscriptPayload.entryJson(own, 0).toString().contains("\"reviewable\":true"))
        assertFalse(JcefTranscriptPayload.entryJson(edit, 0).toString().contains("reviewable"))
    }
}
