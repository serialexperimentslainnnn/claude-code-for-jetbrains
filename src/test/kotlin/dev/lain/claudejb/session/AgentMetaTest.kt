package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * [AgentMeta] against the shape the binary really writes.
 *
 * The fixture below is a verbatim `subagents/agent-*.meta.json` from `claude` 2.1.226 — the sidecar that
 * makes the agent tree data instead of inference.
 */
class AgentMetaTest {

    private val real = """
        {
          "agentType": "general-purpose",
          "description": "Translate erp-sap-standards",
          "toolUseId": "toolu_01SDykjceHBHhGmLKVokVziu",
          "parentAgentId": "afdee29b28705b1c9",
          "spawnDepth": 2
        }
    """.trimIndent()

    @Test
    fun `parses every field the binary writes`() {
        val meta = requireNotNull(AgentMeta.parse("agent-a8bbd2f22", real))
        assertEquals("general-purpose", meta.agentType)
        assertEquals("Translate erp-sap-standards", meta.description)
        assertEquals("toolu_01SDykjceHBHhGmLKVokVziu", meta.toolUseId)
        // The parent chain and the depth are the whole point: system/task_started carries no parent at all,
        // so without these two fields the nesting would have to be reconstructed by joining events.
        assertEquals("afdee29b28705b1c9", meta.parentAgentId)
        assertEquals(2, meta.spawnDepth)
    }

    @Test
    fun `the tab label falls back rather than going blank`() {
        assertEquals("Translate erp-sap-standards", AgentMeta.parse("agent-x", real)!!.label())
        assertEquals("general-purpose", AgentMeta("agent-x", agentType = "general-purpose").label())
        // Last resort is the id: an unlabelled tab is still navigable, an empty one is not.
        assertEquals("agent-x", AgentMeta("agent-x").label())
    }

    @Test
    fun `a partial or unknown sidecar still parses`() {
        // A newer binary adding fields must never cost us the agent, and a missing depth means top level.
        val meta = requireNotNull(AgentMeta.parse("agent-y", """{"description":"x","futureField":{"a":1}}"""))
        assertEquals(1, meta.spawnDepth)
        assertNull(meta.parentAgentId)
    }

    @Test
    fun `corrupt json yields null instead of throwing`() {
        assertNull(AgentMeta.parse("agent-z", "{not json"))
        assertNull(AgentMeta.parse("agent-z", ""))
    }

    @Test
    fun `only the binary's own sidecar names are recognised`() {
        assertEquals("agent-abc", AgentMeta.agentIdOfMetaFile("agent-abc.meta.json"))
        assertNull(AgentMeta.agentIdOfMetaFile("agent-abc.jsonl"))
        assertNull(AgentMeta.agentIdOfMetaFile("notes.meta.json"))
    }
}
