package dev.lain.claudejb.controller.mcp

import dev.lain.claudejb.model.session.launch.IdeRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class IdeRuleCoverageContractTest {

    private val declared: Set<String> = File("src/main/kotlin/dev/lain/claudejb/controller/mcp/tools")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .flatMap { SPEC_NAME.findAll(it.readText()).map { match -> match.groupValues[1] } }
        .toSet()

    @Test
    fun `the scan sees the catalogue`() {
        assertTrue(declared.size >= MIN_TOOLS) { "only ${declared.size} ToolSpec names found: $declared" }
    }

    @Test
    fun `every tool the servers declare is owned by exactly one rule, and every rule's tool exists`() {
        val ruled = IdeRule.entries.flatMap { rule -> rule.tools.map { it to rule.key } }
        val owners = ruled.groupBy({ it.first }, { it.second })
        assertEquals(emptyList<String>(), declared.filter { it !in owners }.sorted()) {
            "A tool exists that no IdeRule names; the rules block never tells Claude when to use it. Add it to the rule of " +
                "its domain in IdeRule.tools and to that rule's sentence in IdeRuleText."
        }
        assertEquals(emptyList<String>(), owners.keys.filter { it !in declared }.sorted()) {
            "An IdeRule names a tool no server declares; the rule promises what the socket cannot deliver."
        }
        assertEquals(emptyList<String>(), owners.filterValues { it.size > 1 }.keys.sorted()) { "a tool is owned by two rules" }
    }

    private companion object {
        const val MIN_TOOLS = 70

        val SPEC_NAME = Regex("""ToolSpec\(\s*"([a-z_]+)"""")
    }
}
