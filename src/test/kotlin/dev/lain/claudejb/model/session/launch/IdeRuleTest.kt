package dev.lain.claudejb.model.session.launch

import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.model.settings.LaunchDefaults
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdeRuleTest {

    @Test
    fun `every rule key is unique and names its server`() {
        assertEquals(IdeRule.entries.size, IdeRule.entries.map { it.key }.toSet().size)
        IdeRule.entries.filter { it.server != null }.forEach { rule ->
            assertTrue(rule.key.startsWith(rule.server!!.key + "."), rule.key)
            assertTrue(rule.tools.isNotEmpty(), rule.key)
        }
        IdeRule.common.forEach { assertTrue(it.key.startsWith("common.") && it.tools.isEmpty(), it.key) }
    }

    @Test
    fun `the settings keep the rules as a csv that survives unknown keys and spaces`() {
        val parsed = IdeRule.parse(" code.read, run.debug ,gone.rule,")
        assertEquals(setOf(IdeRule.CODE_READ, IdeRule.RUN_DEBUG), parsed)
        assertEquals("code.read,run.debug", IdeRule.csv(parsed))
        assertNull(IdeRule.of("nope"))
    }

    @Test
    fun `a rule is active only with its server on, and a common rule only with some server on`() {
        val picked = setOf(IdeRule.CODE_READ, IdeRule.RUN_DEBUG, IdeRule.COMMON_AGENTS)
        assertEquals(emptySet<IdeRule>(), IdeRule.active(picked, emptySet()))
        assertEquals(setOf(IdeRule.CODE_READ, IdeRule.COMMON_AGENTS), IdeRule.active(picked, setOf(IdeServer.CODE)))
        assertEquals(picked, IdeRule.active(picked, setOf(IdeServer.CODE, IdeServer.RUN)))
    }

    @Test
    fun `each of our servers owns rules, and every rule belongs to one of them or to all`() {
        IdeServer.entries.forEach { assertTrue(IdeRule.forServer(it).isNotEmpty(), it.key) }
        assertEquals(IdeRule.entries.toSet(), IdeServer.entries.flatMap { IdeRule.forServer(it) }.toSet() + IdeRule.common)
        assertEquals(IdeServer.entries.map { it.key }, IdeServer.entries.map { it.mcpName })
    }

    @Test
    fun `God Mode is our servers with every rule, it is the default, and the settings default names every rule`() {
        assertEquals(IdeRule.csv(IdeRule.entries), LaunchDefaults.DEFAULT_IDE_RULES)
        val s = ClaudeSettings.State()
        assertTrue(GodMode.isOn(s), "a fresh install is God Mode")
        s.ideMcp.rules = IdeRule.csv(IdeRule.entries - IdeRule.CODE_READ)
        assertFalse(GodMode.isOn(s), "one rule off is not God Mode")
        GodMode.set(s, false)
        assertFalse(s.ideMcp.enabled)
        assertEquals("", s.ideMcp.rules)
        GodMode.set(s, true)
        assertTrue(GodMode.isOn(s))
    }
}
