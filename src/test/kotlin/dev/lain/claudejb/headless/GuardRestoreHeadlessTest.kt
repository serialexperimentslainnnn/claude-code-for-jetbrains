package dev.lain.claudejb.headless

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.permission.PermissionBroker
import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.EntryDTO
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.GuardAlert
import dev.lain.claudejb.settings.GuardAlertLog
import dev.lain.claudejb.settings.SecretStore

class GuardRestoreHeadlessTest : BasePlatformTestCase() {

    private val rule = SecurityRule.DESTRUCTIVE_IAC

    private val savedSession = "restored-session"

    override fun setUp() {
        super.setUp()
        SecretStore.storeOverride = mutableMapOf()
    }

    override fun tearDown() {
        try {
            SecretStore.storeOverride = null
        } finally {
            super.tearDown()
        }
    }

    private val scope get() = ClaudeSettings.getInstance(project).scope

    private fun record(verdict: String, toolUseId: String?, via: String? = null) {
        GuardAlertLog.record(
            scope,
            GuardAlert(
                at = 1,
                rule = rule.name,
                category = rule.category.name,
                verdict = verdict,
                sessionId = savedSession,
                toolUseId = toolUseId,
                via = via,
                tool = "Bash",
                detail = "runs an irreversible destructive operation",
                command = "terraform destroy",
            ),
        )?.get()
    }

    private fun toolRow(id: String) = EntryDTO(speaker = "TOOL", text = "Bash", toolUseId = id)

    private fun restored(dtos: List<EntryDTO>): ClaudeSession {
        val session = ClaudeSession(project, "t")
        session.restore(savedSession, dtos)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        return session
    }

    fun `test a refusal comes back as its own row, anchored to the call it refused`() {
        record(GuardAlert.DENIED, "tu_1")
        val session = restored(listOf(toolRow("tu_0"), toolRow("tu_1"), toolRow("tu_2")))
        try {
            val rows = session.transcript.entries
            assertEquals("the guard's row is added, not folded into the call", 4, rows.size)
            assertEquals(rule.name, rows[2].blockedRule)
            assertEquals(
                "without the command the Whitelist Command link has nothing to file",
                "terraform destroy",
                rows[2].commandText,
            )
        } finally {
            session.dispose()
        }
    }

    fun `test a bypass comes back as a bypass, with the link that can undo it`() {
        record(GuardAlert.ALLOWED, "tu_1", via = PermissionBroker.REMOVE_FROM_WHITELIST)
        val session = restored(listOf(toolRow("tu_1")))
        try {
            val row = session.transcript.entries.last()
            assertEquals(rule.name, row.bypassedRule)
            assertEquals(PermissionBroker.REMOVE_FROM_WHITELIST, row.bypassAction)
        } finally {
            session.dispose()
        }
    }

    fun `test an Allow All given on a card comes back without an undo it could not honour`() {
        record(GuardAlert.ALLOWED, "tu_1", via = PermissionBroker.REVOKE_APPROVAL)
        val session = restored(listOf(toolRow("tu_1")))
        try {
            val row = session.transcript.entries.last()
            assertEquals("it still happened, so it is still reported", rule.name, row.bypassedRule)
            assertNull("that approval died with the IDE; offering to withdraw it would be a lie", row.bypassAction)
        } finally {
            session.dispose()
        }
    }

    fun `test a conversation whose log is empty restores exactly as it did before any of this`() {
        val dtos = listOf(toolRow("tu_1"), toolRow("tu_2"))
        val session = restored(dtos)
        try {
            assertEquals(dtos.size, session.transcript.entries.size)
            assertTrue(session.transcript.entries.all { it.blockedRule == null && it.bypassedRule == null })
        } finally {
            session.dispose()
        }
    }

    fun `test another conversation's alerts are not pulled into this one`() {
        GuardAlertLog.record(
            scope,
            GuardAlert(
                at = 1,
                rule = rule.name,
                category = rule.category.name,
                verdict = GuardAlert.DENIED,
                sessionId = "a-different-conversation",
                toolUseId = "tu_1",
            ),
        )?.get()
        val session = restored(listOf(toolRow("tu_1")))
        try {
            assertEquals(1, session.transcript.entries.size)
        } finally {
            session.dispose()
        }
    }
}
