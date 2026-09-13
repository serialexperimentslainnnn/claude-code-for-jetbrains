package dev.lain.claudejb.model.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IdeRulesAdoptionTest {

    private val current = LaunchDefaults.DEFAULT_IDE_RULES

    @Test
    fun `rules saved under a previous catalogue become the whole current one, so an upgrade lands in God Mode`() {
        val previous = "index.read,debugger.debug,jetbrains.build,common.agents,common.tools,common.fallback,common.report"
        assertEquals(current, IdeRulesAdoption.adopted(previous, ""))
        val s = ClaudeSettings.State().apply { ideMcp.rules = previous }
        IdeRulesAdoption.adopt(s)
        assertEquals(current, s.ideMcp.rules)
        assertEquals(current, s.ideMcp.catalogue)
    }

    @Test
    fun `a state with no catalogue recorded comes from an older build and lands in God Mode, servers on`() {
        val older = "code.read,code.search,run.build,vcs.read,common.agents"
        assertEquals(current, IdeRulesAdoption.adopted(older, ""))
        val s = ClaudeSettings.State().apply {
            ideMcp.enabled = false
            ideMcp.rules = ""
        }
        IdeRulesAdoption.adopt(s)
        assertEquals(true, s.ideMcp.enabled)
        assertEquals(current, s.ideMcp.rules)
    }

    @Test
    fun `a rule the catalogue did not have yet joins on, while what the user turned off stays off`() {
        val olderCatalogue = current.replace("vcs.log_ops,", "").replace("ops.window,", "")
        val chosen = olderCatalogue.replace("code.psi,", "").replace("common.tools,", "")
        val adopted = IdeRulesAdoption.adopted(chosen, olderCatalogue)
        val keys = adopted.split(',')
        assertEquals(listOf(true, true), listOf("vcs.log_ops" in keys, "ops.window" in keys), adopted)
        assertEquals(listOf(false, false), listOf("code.psi" in keys, "common.tools" in keys), adopted)
        assertEquals(current.split(',').filter { it in keys }, keys, "the catalogue's order is kept")
    }

    @Test
    fun `a choice made on the current catalogue is kept, everything off included`() {
        listOf("", "code.read,common.agents", " vcs.write , ops.ide ,", current).forEach { csv ->
            val s = ClaudeSettings.State().apply {
                ideMcp.rules = csv
                ideMcp.catalogue = current
            }
            IdeRulesAdoption.adopt(s)
            assertEquals(csv, s.ideMcp.rules, csv)
        }
    }
}
