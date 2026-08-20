package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.protocol.ModelInfo
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.SecuritySuspensions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JcefSettingsMenuTest {

    private fun models() = listOf(
        ModelInfo("opus[1m]", "Opus (1M context)", "Opus 5 with 1M context · Best for everyday, complex tasks"),
        ModelInfo("sonnet", "Sonnet", "Sonnet 5 · Efficient for routine tasks"),
        ModelInfo("default", "Recommended", "Recommended · whatever the binary currently favours"),
    )

    private fun modelIds() = models().map { it.value }

    private fun selected(model: String = "opus[1m]", effort: String? = "high", mode: String = "default") =
        JcefSettingsMenu.Selected(models = models(), model = model, effort = effort, mode = mode)

    private fun menu(
        state: ClaudeSettings.State = ClaudeSettings.State(),
        selected: JcefSettingsMenu.Selected = selected(),
    ): List<JsonObject> = JcefSettingsMenu.json(state, selected).map { it.jsonObject }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    private fun write(state: ClaudeSettings.State, key: String, on: Boolean) =
        JcefSettingsMenu.apply(state, key, on, modelIds())

    @Test
    fun `the groups are drawn in the declared order`() {
        assertEquals(
            listOf(
                "Model", "Effort", "Permission mode", "Chat", "Guard mode", "Security",
                "Setting sources", "Allowed tools", "Disallowed tools", "Always allowed tools", "MCP",
            ),
            menu().map { it.str("group") }.distinct(),
        )
    }

    @Test
    fun `each row carries the type and the deferral of its group`() {
        val byGroup = menu().groupBy { it.str("group") }

        listOf("Model", "Effort", "Permission mode").forEach { group ->
            val rows = byGroup.getValue(group)
            assertTrue(rows.all { it.str("type") == "radio" }, "$group must be a radio group")
            assertTrue(rows.none { it.bool("deferred") }, "$group takes effect on the live session")
        }

        listOf("Chat", "Security", "Always allowed tools").forEach { group ->
            val rows = byGroup.getValue(group)
            assertTrue(rows.all { it.str("type") == "check" }, "$group must be a checkbox group")
            assertTrue(rows.none { it.bool("deferred") }, "$group takes effect immediately")
        }

        listOf("Setting sources", "Allowed tools", "Disallowed tools", "MCP").forEach { group ->
            val rows = byGroup.getValue(group)
            assertTrue(rows.all { it.str("type") == "check" }, "$group must be a checkbox group")
            assertTrue(rows.all { it.bool("deferred") }, "$group only applies to a new chat")
        }
    }

    @Test
    fun `the model group offers the catalogue, without the floating alias, and marks the live selection`() {
        val model = menu(selected = selected(model = "sonnet")).filter { it.str("group") == "Model" }

        assertEquals(listOf("model:opus[1m]", "model:sonnet"), model.map { it.str("key") })
        assertEquals(listOf("Opus 5 with 1M context", "Sonnet 5"), model.map { it.str("label") })
        assertEquals(listOf(false, true), model.map { it.bool("on") })
    }

    @Test
    fun `the effort and mode groups mark what the SESSION has, not what is stored`() {
        val state = ClaudeSettings.State().apply {
            effort = "low"
            permissionMode = "default"
        }
        val rows = menu(state, selected(effort = "max", mode = "plan"))

        assertEquals("effort:max", rows.single { it.str("group") == "Effort" && it.bool("on") }.str("key"))
        assertEquals("mode:plan", rows.single { it.str("group") == "Permission mode" && it.bool("on") }.str("key"))
    }

    @Test
    fun `a checkbox row reflects the CSV field it writes`() {
        val state = ClaudeSettings.State().apply {
            allowedTools = "Read, Grep"
            alwaysAllowTools = "Bash"
        }
        val on = menu(state).filter { it.bool("on") }.map { it.str("key") }

        assertTrue(on.containsAll(listOf("allow:Read", "allow:Grep", "always:Bash")))
        assertFalse(on.contains("allow:Bash"))
        assertFalse(on.contains("deny:Read"))
    }

    @Test
    fun `every key the menu emits has a destination that accepts it`() {
        menu().forEach { row ->
            val key = row.str("key")
            val accepted = JcefSettingsMenu.alwaysAllowTool(key) != null ||
                write(ClaudeSettings.State(), key, true)
            assertTrue(accepted, "no destination accepts the menu key '$key'")
        }
    }

    @Test
    fun `a suffix outside its catalogue is refused and writes nothing`() {
        val state = ClaudeSettings.State()
        val before = listOf(state.model, state.effort, state.permissionMode, state.settingSources)

        listOf(
            "model:gpt-5",
            "effort:turbo",
            "mode:letMeIn",
            "source:global",
            "allow:rm",
            "deny:sudo",
            "colour:red",
            "notASwitch",
        ).forEach { key ->
            assertFalse(write(state, key, true), "'$key' must not be accepted")
        }

        assertEquals(before, listOf(state.model, state.effort, state.permissionMode, state.settingSources))
        assertEquals("", state.allowedTools)
        assertEquals("", state.disallowedTools)
    }

    @Test
    fun `an always-allow key is only recognised for a tool this build knows`() {
        assertEquals("Bash", JcefSettingsMenu.alwaysAllowTool("always:Bash"))
        assertNull(JcefSettingsMenu.alwaysAllowTool("always:rm -rf /"))
        assertNull(JcefSettingsMenu.alwaysAllowTool("always:"))
        assertNull(JcefSettingsMenu.alwaysAllowTool("allow:Bash"))
        assertFalse(write(ClaudeSettings.State(), "always:Bash", true))
    }

    @Test
    fun `a CSV field gains and loses a value without duplicates or orphan commas`() {
        val state = ClaudeSettings.State()

        assertTrue(write(state, "allow:Bash", true))
        assertEquals("Bash", state.allowedTools)

        assertTrue(write(state, "allow:Read", true))
        assertEquals("Bash,Read", state.allowedTools)

        assertTrue(write(state, "allow:Bash", true))
        assertEquals("Bash,Read", state.allowedTools)

        assertTrue(write(state, "allow:Bash", false))
        assertEquals("Read", state.allowedTools)

        assertTrue(write(state, "allow:Read", false))
        assertEquals("", state.allowedTools)

        assertTrue(write(state, "allow:Grep", false))
        assertEquals("", state.allowedTools)
    }

    @Test
    fun `a CSV field written by hand is normalised rather than appended to`() {
        val state = ClaudeSettings.State().apply { disallowedTools = " Bash ,, Read ," }

        assertTrue(write(state, "deny:Grep", true))
        assertEquals("Bash,Read,Grep", state.disallowedTools)
    }

    @Test
    fun `setting sources are their own catalogue`() {
        val state = ClaudeSettings.State()

        assertTrue(write(state, "source:project", false))
        assertEquals("user,local", state.settingSources)

        assertTrue(write(state, "source:project", true))
        assertEquals("user,local,project", state.settingSources)
    }

    @Test
    fun `a radio group keeps exactly one value`() {
        val state = ClaudeSettings.State()

        assertTrue(write(state, "mode:acceptEdits", true))
        assertEquals("acceptEdits", state.permissionMode)

        assertTrue(write(state, "mode:plan", true))
        assertEquals("plan", state.permissionMode)

        assertTrue(write(state, "model:sonnet", true))
        assertEquals("sonnet", state.model)

        assertTrue(write(state, "effort:max", true))
        assertEquals("max", state.effort)
    }

    @Test
    fun `switching a radio option off leaves the group as it was`() {
        val state = ClaudeSettings.State().apply { permissionMode = "plan" }

        assertTrue(write(state, "mode:plan", false))
        assertEquals("plan", state.permissionMode)

        assertTrue(write(state, "mode:acceptEdits", false))
        assertEquals("plan", state.permissionMode)
    }

    @Test
    fun `the plain switches write their own field and nothing else`() {
        val state = ClaudeSettings.State()

        assertTrue(write(state, "ideMcp", true))
        assertTrue(state.ideMcpEnabled)

        assertTrue(write(state, "strictMcp", true))
        assertTrue(state.strictMcpConfig)

        assertTrue(write(state, "reduceMotion", true))
        assertTrue(state.reduceMotion)
    }

    @Test
    fun `turning a rule off adds it to the disabled set, and on takes it out again`() {
        val state = ClaudeSettings.State()

        assertTrue(write(state, "rule:TEMP_DIR", false))
        assertEquals("TEMP_DIR", state.disabledSecurityRules)

        assertTrue(write(state, "rule:CREDENTIALS", false))
        assertEquals("CREDENTIALS,TEMP_DIR", state.disabledSecurityRules)

        assertTrue(write(state, "rule:TEMP_DIR", true))
        assertEquals("CREDENTIALS", state.disabledSecurityRules)
    }

    @Test
    fun `a rule id outside the catalogue is refused and writes nothing`() {
        val state = ClaudeSettings.State().apply { disabledSecurityRules = "TEMP_DIR" }

        assertFalse(write(state, "rule:NOT_A_RULE", false))
        assertFalse(write(state, "rule:temp_dir", false))
        assertEquals("TEMP_DIR", state.disabledSecurityRules)
    }

    @Test
    fun `every rule of every category has a row, and each carries its category as its sub-level`() {
        val rows = menu().filter { it.str("group") == "Security" && it.str("key") != "guard" }
        assertEquals(SecurityRule.entries.size, rows.size)
        rows.forEach { row ->
            val rule = SecurityRule.from(row.str("key").removePrefix("rule:"))
            assertNotNull(rule, row.toString())
            assertEquals(rule!!.category.label, row.str("sub"))
            assertEquals(rule.label, row.str("label"))
            assertTrue(row.bool("on"), row.str("key"))
        }
    }

    @Test
    fun `the guard's own mode is one choice of three, and Enforcing by default`() {
        val rows = menu().filter { it.str("group") == "Guard mode" }

        assertEquals(listOf("Enforcing", "Permissive", "Allow All"), rows.map { it.str("label") })
        assertTrue(rows.all { it.str("type") == "radio" }, "three ways to answer one question, not three switches")
        assertEquals("Enforcing", rows.single { it.bool("on") }.str("label"))
    }

    @Test
    fun `choosing Allow All here is Forever, because a menu cannot ask for how long`() {
        val state = ClaudeSettings.State()

        assertTrue(write(state, "guardmode:allowAll", true))
        assertEquals("allowAll", state.guardMode)
        assertEquals(0L, state.guardDisabledUntil, "a menu must not invent a deadline")
    }

    @Test
    fun `choosing Enforcing ends an Allow All that is still running`() {
        val state = ClaudeSettings.State()
        SecuritySuspensions.guardOff(state, SecuritySuspensions.Duration.HOURS_8, System.currentTimeMillis())

        assertTrue(write(state, "guardmode:enforcing", true))

        assertFalse(
            SecuritySuspensions.guardSuspended(state, System.currentTimeMillis()),
            "a menu saying Enforcing over a live Allow All is a menu telling the user something untrue",
        )
    }

    @Test
    fun `a mode nobody offers is refused and writes nothing`() {
        val state = ClaudeSettings.State()

        assertFalse(write(state, "guardmode:whatever", true))
        assertEquals("enforcing", state.guardMode)
    }
}
