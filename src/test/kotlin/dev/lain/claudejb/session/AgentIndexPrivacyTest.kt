package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What [PluginAgentIndex] is allowed to persist, pinned as a contract.
 *
 * Two rules, and both came from the user. **Nothing goes into the project's `.idea/`**: it is shared, gets
 * committed by accident and is routinely synced, so anything there is effectively published — the index
 * lives under `~/.claude`, private to the user and where this data already is. And even there it carries
 * **ids and two booleans**: an agent's description ("Translate erp-sap-standards") already says what the
 * user is working on, and a prompt or transcript says far more. Titles and transcripts are read from the
 * binary's own files on demand, so copying them buys nothing and creates a second thing to leak or go stale.
 *
 * This test exists to stop a future "just cache the title so the tab restores faster" from quietly turning
 * an index into a data store.
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

    @Test
    fun `the index lives under the user's claude home, never in the project`() {
        // The location IS the privacy decision, so it is pinned rather than left to a comment.
        val home = PluginAgentIndex.homeOverride
        assertTrue(home != null && home.endsWith("/.claude"), "expected ~/.claude, got $home")
    }
}
