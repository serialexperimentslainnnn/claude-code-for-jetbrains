package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.permission.PermissionBroker
import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.settings.GuardAlert
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JcefGuardDataTest {

    private fun alert(
        verdict: String,
        via: String? = null,
        rule: SecurityRule = SecurityRule.CREDENTIALS,
        at: Long = 1_000L,
        toolUseId: String? = "tu_1",
        command: String? = "cat ~/.ssh/id_ed25519",
        detail: String? = "reads a credential file",
    ) = GuardAlert(
        at = at,
        rule = rule.name,
        category = rule.category.name,
        verdict = verdict,
        sessionId = "s1",
        toolUseId = toolUseId,
        via = via,
        tool = "Bash",
        detail = detail,
        command = command,
    )

    private fun payload(
        alerts: List<GuardAlert>,
        recorded: Int = alerts.size,
        dropped: Int = 0,
        recording: Boolean = true,
        max: Int = 500,
    ) = JcefGuardData.guardJson(alerts, recorded, dropped, recording, max)

    @Test
    fun `a refusal is blocked and an approval card is blocked too — neither one ran unattended`() {
        assertEquals(JcefGuardData.BLOCKED, JcefGuardData.tabOf(alert(GuardAlert.DENIED)))
        assertEquals(JcefGuardData.BLOCKED, JcefGuardData.tabOf(alert(GuardAlert.ASKED)))
    }

    @Test
    fun `the reason a call got through is the tab it lands in`() {
        assertEquals(
            JcefGuardData.WHITELISTED,
            JcefGuardData.tabOf(alert(GuardAlert.ALLOWED, PermissionBroker.REMOVE_FROM_WHITELIST)),
        )
        assertEquals(
            JcefGuardData.DISABLED,
            JcefGuardData.tabOf(alert(GuardAlert.ALLOWED, PermissionBroker.ENABLE_GUARD)),
        )
        assertEquals(
            JcefGuardData.ALLOWED,
            JcefGuardData.tabOf(alert(GuardAlert.ALLOWED, PermissionBroker.REVOKE_APPROVAL)),
        )
        assertEquals(JcefGuardData.ALLOWED, JcefGuardData.tabOf(alert(GuardAlert.ALLOWED)))
    }

    @Test
    fun `every alert lands in exactly one of the four tabs, so none can go missing from the view`() {
        val everything = listOf(
            alert(GuardAlert.DENIED),
            alert(GuardAlert.ASKED),
            alert(GuardAlert.ALLOWED),
            alert(GuardAlert.ALLOWED, PermissionBroker.ENABLE_GUARD),
            alert(GuardAlert.ALLOWED, PermissionBroker.REMOVE_FROM_WHITELIST),
            alert(GuardAlert.ALLOWED, PermissionBroker.REVOKE_APPROVAL),
            alert("SOMETHING_A_LATER_BUILD_INVENTS", "an-action-this-build-does-not-know"),
        )
        val tabs = setOf(
            JcefGuardData.BLOCKED,
            JcefGuardData.ALLOWED,
            JcefGuardData.WHITELISTED,
            JcefGuardData.DISABLED,
        )

        everything.forEach { assertTrue(JcefGuardData.tabOf(it) in tabs, "unplaced alert: $it") }

        val json = payload(everything)
        val counted = json["tabs"]!!.jsonArray.sumOf { it.jsonObject["count"]!!.jsonPrimitive.int }
        assertEquals(everything.size, counted, "an alert counted in no tab is an alert nobody can see")
    }

    @Test
    fun `the newest decision is the first one read`() {
        val json = payload(
            listOf(
                alert(GuardAlert.DENIED, at = 1_000L, toolUseId = "old"),
                alert(GuardAlert.DENIED, at = 3_000L, toolUseId = "new"),
                alert(GuardAlert.DENIED, at = 2_000L, toolUseId = "mid"),
            ),
        )
        val order = json["entries"]!!.jsonArray.map { it.jsonObject["at"]!!.jsonPrimitive.long }

        assertEquals(listOf(3_000L, 2_000L, 1_000L), order)
    }

    @Test
    fun `the window says what it is showing and what the ring can hold`() {
        val json = payload(listOf(alert(GuardAlert.DENIED)), recorded = 1, max = 500)
        val window = json["window"]!!.jsonObject

        assertEquals(1, window["kept"]!!.jsonPrimitive.int)
        assertEquals(500, window["max"]!!.jsonPrimitive.int)
    }

    @Test
    fun `an alert the store never took is counted as dropped rather than silently missing`() {
        val json = payload(alerts = emptyList(), recorded = 4, dropped = 4, recording = false)
        val window = json["window"]!!.jsonObject

        assertFalse(json["recording"]!!.jsonPrimitive.boolean, "the view must be able to say the log is deaf")
        assertEquals(4, window["dropped"]!!.jsonPrimitive.int)
        assertEquals(0, window["missing"]!!.jsonPrimitive.int, "a dropped alert never reached the ring")
    }

    @Test
    fun `an alert the store took and no longer hands back is counted as missing`() {
        val window = payload(alerts = listOf(alert(GuardAlert.DENIED)), recorded = 9, dropped = 2)["window"]!!
            .jsonObject

        assertEquals(6, window["missing"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a count that cannot be reconciled never reports a negative loss`() {
        val window = payload(alerts = List(3) { alert(GuardAlert.DENIED, at = it.toLong()) }, recorded = 0)["window"]!!
            .jsonObject

        assertEquals(0, window["missing"]!!.jsonPrimitive.int)
    }

    @Test
    fun `an entry carries the rule as the user reads it and as the host keys it`() {
        val entry = payload(listOf(alert(GuardAlert.DENIED)))["entries"]!!.jsonArray[0].jsonObject

        assertEquals("CREDENTIALS", entry["rule"]!!.jsonPrimitive.content)
        assertEquals(SecurityRule.CREDENTIALS.label, entry["ruleLabel"]!!.jsonPrimitive.content)
        assertEquals(SecurityRule.CREDENTIALS.category.label, entry["category"]!!.jsonPrimitive.content)
        assertEquals("Refused", entry["verdictLabel"]!!.jsonPrimitive.content)
        assertEquals("cat ~/.ssh/id_ed25519", entry["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a rule this build no longer has still draws, under the name it was logged with`() {
        val stale = GuardAlert(
            at = 5L,
            rule = "A_RULE_A_LATER_BUILD_ADDED",
            category = "SENSITIVE_DATA",
            verdict = GuardAlert.DENIED,
        )
        val entry = payload(listOf(stale))["entries"]!!.jsonArray[0].jsonObject

        assertEquals("A_RULE_A_LATER_BUILD_ADDED", entry["ruleLabel"]!!.jsonPrimitive.content)
        assertEquals("SENSITIVE_DATA", entry["category"]!!.jsonPrimitive.content)
        assertFalse(
            entry["explainable"]!!.jsonPrimitive.boolean,
            "there is nothing to explain about a rule this build cannot describe",
        )
    }

    @Test
    fun `only a blocked entry offers the question, because only a block has a rewrite`() {
        val blocked = payload(listOf(alert(GuardAlert.DENIED)))["entries"]!!.jsonArray[0].jsonObject
        val allowed = payload(listOf(alert(GuardAlert.ALLOWED)))["entries"]!!.jsonArray[0].jsonObject

        assertTrue(blocked["explainable"]!!.jsonPrimitive.boolean)
        assertFalse(allowed["explainable"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `an absent field is omitted rather than drawn empty`() {
        val bare = GuardAlert(at = 1L, rule = "CREDENTIALS", category = "SENSITIVE_DATA", verdict = GuardAlert.DENIED)
        val entry = payload(listOf(bare))["entries"]!!.jsonArray[0].jsonObject

        assertNull(entry["command"])
        assertNull(entry["detail"])
        assertNull(entry["tool"])
        assertNull(entry["via"])
    }

    @Test
    fun `the id survives a refresh, so the question is asked about the entry that was pressed`() {
        val one = alert(GuardAlert.DENIED, at = 7L, toolUseId = "tu_9")
        val same = alert(GuardAlert.DENIED, at = 7L, toolUseId = "tu_9", command = "cat other")
        val other = alert(GuardAlert.DENIED, at = 8L, toolUseId = "tu_9")

        assertEquals(JcefGuardData.idOf(one), JcefGuardData.idOf(same))
        assertTrue(JcefGuardData.idOf(one) != JcefGuardData.idOf(other))
        assertEquals(
            JcefGuardData.idOf(one),
            payload(listOf(one))["entries"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `the four tabs are always offered, so an empty one is a statement rather than a gap`() {
        val ids = payload(emptyList())["tabs"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

        assertEquals(
            listOf(
                JcefGuardData.BLOCKED,
                JcefGuardData.ALLOWED,
                JcefGuardData.WHITELISTED,
                JcefGuardData.DISABLED,
            ),
            ids,
        )
    }
}
