package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

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
        assertEquals("afdee29b28705b1c9", meta.parentAgentId)
        assertEquals(2, meta.spawnDepth)
    }

    @Test
    fun `the tab label falls back rather than going blank`() {
        assertEquals("Translate erp-sap-standards", AgentMeta.parse("agent-x", real)!!.label())
        assertEquals("general-purpose", AgentMeta("agent-x", agentType = "general-purpose").label())
        assertEquals("agent-x", AgentMeta("agent-x").label())
    }

    @Test
    fun `a partial or unknown sidecar still parses`() {
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
    fun `the id is the bare one, matching what parentAgentId uses`() {
        assertEquals("abc", AgentMeta.agentIdOfMetaFile("agent-abc.meta.json"))
        assertEquals("agent-abc.jsonl", AgentMeta.transcriptFile("abc"))
        assertNull(AgentMeta.agentIdOfMetaFile("agent-abc.jsonl"))
        assertNull(AgentMeta.agentIdOfMetaFile("notes.meta.json"))
    }
}
