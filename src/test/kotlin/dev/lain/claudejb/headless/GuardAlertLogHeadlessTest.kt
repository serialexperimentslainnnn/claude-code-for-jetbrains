package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.settings.GuardAlert
import dev.lain.claudejb.settings.GuardAlertLog
import dev.lain.claudejb.settings.SecretStore
import dev.lain.claudejb.settings.SettingsScope

class GuardAlertLogHeadlessTest : BasePlatformTestCase() {

    private val scope = SettingsScope("log-under-test")
    private val other = SettingsScope("a-different-project")
    private val rule = SecurityRule.DESTRUCTIVE_IAC

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

    private fun alert(at: Long, command: String = "terraform destroy", session: String = "s1") = GuardAlert(
        at = at,
        rule = rule.name,
        category = rule.category.name,
        verdict = GuardAlert.DENIED,
        sessionId = session,
        toolUseId = "tu_$at",
        tool = "Bash",
        detail = "runs an irreversible destructive operation",
        command = command,
    )

    private fun record(scope: SettingsScope, alert: GuardAlert) {
        GuardAlertLog.record(scope, alert)?.get()
    }

    fun `test an alert survives the round trip whole`() {
        record(scope, alert(1))

        val kept = GuardAlertLog.forSession(scope, "s1").single()
        assertEquals(rule.name, kept.rule)
        assertEquals(rule.category.name, kept.category)
        assertEquals(GuardAlert.DENIED, kept.verdict)
        assertEquals("tu_1", kept.toolUseId)
        assertEquals("terraform destroy", kept.command)
        assertEquals("runs an irreversible destructive operation", kept.detail)
    }

    fun `test the log is per project, like the settings beside it`() {
        record(scope, alert(1, command = "mine"))
        record(other, alert(2, command = "theirs"))

        assertEquals(listOf("mine"), GuardAlertLog.forSession(scope, "s1").map { it.command })
        assertEquals(listOf("theirs"), GuardAlertLog.forSession(other, "s1").map { it.command })
    }

    fun `test one conversation's alerts are separable from another's`() {
        record(scope, alert(1, session = "s1"))
        record(scope, alert(2, session = "s2"))

        assertEquals(listOf("tu_1"), GuardAlertLog.forSession(scope, "s1").map { it.toolUseId })
        assertEquals(listOf("tu_2"), GuardAlertLog.forSession(scope, "s2").map { it.toolUseId })
    }

    fun `test the ring drops the oldest and keeps the newest`() {
        val over = GuardAlertLog.MAX_ENTRIES + 10
        (1..over).forEach { record(scope, alert(it.toLong())) }

        val kept = GuardAlertLog.forSession(scope, "s1")
        assertEquals(GuardAlertLog.MAX_ENTRIES, kept.size)
        assertEquals("the oldest ten went, which is what a bound is for", "tu_11", kept.first().toolUseId)
        assertEquals("tu_$over", kept.last().toolUseId)
    }

    fun `test a log that will not parse starts again instead of refusing to record`() {
        SecretStore.set(scope.guardLogName, "this is not a log")

        record(scope, alert(1))

        assertEquals(
            "losing history is a worse day than never recording anything again",
            listOf("tu_1"),
            GuardAlertLog.forSession(scope, "s1").map { it.toolUseId },
        )
    }

    fun `test clearing one scope leaves the other alone`() {
        record(scope, alert(1))
        record(other, alert(2))

        GuardAlertLog.clear(scope)

        assertTrue(GuardAlertLog.forSession(scope, "s1").isEmpty())
        assertEquals(1, GuardAlertLog.forSession(other, "s1").size)
    }

    fun `test signing out does not take the log with it`() {
        record(scope, alert(1))
        SecretStore.set(SecretStore.OAUTH_TOKEN, "fixture-value-not-a-credential")

        SecretStore.clearAll()

        assertEquals(
            "clearAll clears credentials; an audit trail is not one",
            1,
            GuardAlertLog.forSession(scope, "s1").size,
        )
    }
}
