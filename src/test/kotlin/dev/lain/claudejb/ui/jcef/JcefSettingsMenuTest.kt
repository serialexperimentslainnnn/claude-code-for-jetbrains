package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.protocol.ModelInfo
import dev.lain.claudejb.settings.ClaudeSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The composer's ⚙ menu, tested on the pure half.
 *
 * The menu shows three values that belong to the running session, so [JcefSettingsMenu.json] has an overload
 * that takes them as data ([JcefSettingsMenu.Selected]) instead of a `ClaudeSession` — which is what lets the
 * rows, their keys and every write be exercised without an IDE.
 *
 * The subject under test is a settings write driven by strings that arrive from a browser, and six of the rows
 * are the deterministic guard's own switches while a seventh pre-authorises a tool. So the assertions are
 * about refusal as much as about writing: an unrecognised suffix must change nothing, and every key the menu
 * emits must have a destination that accepts it.
 */
class JcefSettingsMenuTest {

    // The catalogue the binary reports, plus the floating alias the menu must not offer as a choice.
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

    /** [JcefSettingsMenu.apply] against this catalogue. Named apart from `kotlin.apply`, used below. */
    private fun write(state: ClaudeSettings.State, key: String, on: Boolean) =
        JcefSettingsMenu.apply(state, key, on, modelIds())

    // ── The rows ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the groups are drawn in the declared order`() {
        assertEquals(
            listOf(
                "Model", "Effort", "Permission mode", "Chat", "Security",
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

        // A check that applies now: the guard reads it per call, and the auto-approval set is read per call too.
        listOf("Chat", "Security", "Always allowed tools").forEach { group ->
            val rows = byGroup.getValue(group)
            assertTrue(rows.all { it.str("type") == "check" }, "$group must be a checkbox group")
            assertTrue(rows.none { it.bool("deferred") }, "$group takes effect immediately")
        }

        // …and a check that is a launch flag: it only reaches the binary through the next `applyTo`.
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
        // The stored defaults deliberately differ from the session's live values.
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

    // ── Writing ──────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The defect this prevents is a key renamed on one side and not the other. It is not cosmetic here: six of
     * these rows are the guard's own switches and one pre-authorises a tool, so a row that reaches no
     * destination is a security control the user can press while nothing answers.
     *
     * The two destinations are exactly the ones `ChatBridgeRouter.writeSettingsToggle` tries, in its order.
     */
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
        // It is not a field of the settings document, so the document's writer must not claim it either.
        assertFalse(write(ClaudeSettings.State(), "always:Bash", true))
    }

    @Test
    fun `a CSV field gains and loses a value without duplicates or orphan commas`() {
        val state = ClaudeSettings.State()

        assertTrue(write(state, "allow:Bash", true))
        assertEquals("Bash", state.allowedTools)

        assertTrue(write(state, "allow:Read", true))
        assertEquals("Bash,Read", state.allowedTools)

        assertTrue(write(state, "allow:Bash", true)) // idempotent: pressing an already-checked row
        assertEquals("Bash,Read", state.allowedTools)

        assertTrue(write(state, "allow:Bash", false))
        assertEquals("Read", state.allowedTools)

        assertTrue(write(state, "allow:Read", false))
        assertEquals("", state.allowedTools)

        // Removing something that was never there leaves the field alone rather than emptying it.
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
        val state = ClaudeSettings.State() // "user,project,local"

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

    /**
     * A deselection has no valid outcome for a group that must always hold a value, so it is accepted and
     * ignored. Accepted, because the key exists — answering false would make the caller log a rename that
     * never happened; ignored, because clearing the field would mean launching the next chat with no
     * permission mode, which is the "reset to default" bug wearing a different hat.
     */
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

        assertTrue(write(state, "blockDangerous", false))
        assertFalse(state.securityBlockDangerousCommands)
        assertTrue(state.securityBlockCredentials)

        assertTrue(write(state, "ideMcp", true))
        assertTrue(state.ideMcpEnabled)

        assertTrue(write(state, "strictMcp", true))
        assertTrue(state.strictMcpConfig)

        assertTrue(write(state, "reduceMotion", true))
        assertTrue(state.reduceMotion)
    }
}
