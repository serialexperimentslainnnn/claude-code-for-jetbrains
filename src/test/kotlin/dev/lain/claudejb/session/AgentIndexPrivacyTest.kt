package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What [PluginAgentIndex] is allowed to write into `.idea/workspace.xml`, pinned as a contract.
 *
 * The project directory is shared, gets committed by accident and is routinely synced, so anything written
 * there is effectively published. An agent's description alone ("Translate erp-sap-standards") says what the
 * user is working on; a prompt or a transcript says far more. All of that already lives in the binary's files
 * under `~/.claude`, which is the source of truth the plugin reads anyway — so the index carries **ids and
 * two booleans**, and this test exists to keep a future "just add the title so the tab restores faster" from
 * quietly turning workspace state into a data leak.
 */
class AgentIndexPrivacyTest {

    @Test
    fun `the persisted form carries ids and flags only`() {
        val encoded = PluginAgentIndex.encode(
            mapOf(
                "5f2b-session" to listOf(
                    PluginAgentIndex.AgentRecord("agent-a1", open = true),
                    PluginAgentIndex.AgentRecord("agent-b2", open = false, closedByUser = true),
                ),
            ),
        )
        // Exactly the three fields of AgentRecord, and no room for a fourth to sneak in unnoticed.
        assertTrue(encoded.contains("agent-a1"))
        assertTrue(encoded.contains("closedByUser"))
        setOf("description", "prompt", "text", "transcript", "title", "summary", "content")
            .forEach { assertFalse(encoded.contains(it), "persisted index must not carry '$it'") }
    }

    @Test
    fun `a round trip preserves the tab state and nothing more`() {
        val original = mapOf(
            "s1" to listOf(PluginAgentIndex.AgentRecord("agent-a", open = false, closedByUser = true)),
        )
        assertEquals(original, PluginAgentIndex.decode(PluginAgentIndex.encode(original)))
    }

    @Test
    fun `corrupt or blank state never throws`() {
        assertTrue(PluginAgentIndex.decode("").isEmpty())
        assertTrue(PluginAgentIndex.decode("{not json").isEmpty())
    }
}
