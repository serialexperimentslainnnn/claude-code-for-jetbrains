package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LegacyModelsTest {

    private val liveCatalog = listOf("default", "opus[1m]", "claude-fable-5[1m]", "sonnet", "haiku")

    @Test
    fun `no entry collides with the current catalog`() {
        val offered = LegacyModels.offeredAlongside(liveCatalog).map { it.value }
        assertEquals(LegacyModels.ALL.size, offered.size, "nothing in the live catalog should be filtered out")
        liveCatalog.forEach { current ->
            assertFalse(current in offered, "$current is offered by the binary and must not be duplicated here")
        }
    }

    @Test
    fun `a catalog that starts listing an older id wins, and the entry drops out`() {
        val offered = LegacyModels.offeredAlongside(liveCatalog + "claude-opus-4-5").map { it.value }
        assertFalse("claude-opus-4-5" in offered)
        assertTrue("claude-opus-4-1" in offered, "the rest of the list is unaffected")
    }

    @Test
    fun `every entry is a previous generation, never the current one`() {
        val current = listOf("opus-5", "fable-5", "sonnet-5", "haiku-4-5")
        LegacyModels.ALL.forEach { entry ->
            current.forEach { tier ->
                assertFalse(entry.value.contains(tier), "${entry.value} names a current model")
            }
        }
    }

    @Test
    fun `labels are curated because deriving them is wrong for the 3-x naming scheme`() {
        assertEquals("Sonnet 3.5", LegacyModels.labelFor("claude-3-5-sonnet"))
        assertEquals("Opus 4.7", LegacyModels.labelFor("claude-opus-4-7"))
        assertNull(LegacyModels.labelFor("opus[1m]"), "a current model is not ours to label")
        assertNull(LegacyModels.labelFor(null))
    }

    @Test
    fun `every id is one the binary actually knows, not one that fits the numbering pattern`() {
        val knownToBinary = setOf(
            "claude-opus-5", "claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6", "claude-opus-4-5",
            "claude-opus-4-1", "claude-opus-4-0", "claude-opus-4",
            "claude-sonnet-5", "claude-sonnet-4-6", "claude-sonnet-4-5", "claude-sonnet-4-0", "claude-sonnet-4",
            "claude-3-7-sonnet", "claude-3-5-sonnet",
            "claude-haiku-4-5", "claude-haiku-4", "claude-3-5-haiku",
        )
        LegacyModels.ALL.forEach { entry ->
            assertTrue(entry.value in knownToBinary, "${entry.value} is in no model table of the shipped binary")
        }
    }

    @Test
    fun `ids are unique`() {
        assertEquals(LegacyModels.ALL.size, LegacyModels.ALL.map { it.value }.toSet().size)
    }
}
