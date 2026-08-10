package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [LegacyModels] — the curated "Other models" list.
 *
 * The list itself is data, so what is worth pinning is the two rules that keep it honest: it must never
 * duplicate a model the binary already offers, and it must never smuggle in a CURRENT model, which is what
 * would turn an append-only historical list back into something that goes stale.
 */
class LegacyModelsTest {

    /** The catalog the binary really returns (claude 2.1.223), verified via the `initialize` control request. */
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
        // The graceful path for the day the binary decides to offer one of these itself: it appears once, from
        // the catalog, with the catalog's own label — not twice.
        val offered = LegacyModels.offeredAlongside(liveCatalog + "claude-opus-4-5").map { it.value }
        assertFalse("claude-opus-4-5" in offered)
        assertTrue("claude-opus-4-1" in offered, "the rest of the list is unaffected")
    }

    @Test
    fun `every entry is a previous generation, never the current one`() {
        // The distinction this whole file rests on: historical ids are immutable facts and can be listed;
        // naming the CURRENT tier is what went stale in 4.3.3 ("Default · Opus 4.8") and is banned here.
        val current = listOf("opus-5", "fable-5", "sonnet-5", "haiku-4-5")
        LegacyModels.ALL.forEach { entry ->
            current.forEach { tier ->
                assertFalse(entry.value.contains(tier), "${entry.value} names a current model")
            }
        }
    }

    @Test
    fun `labels are curated because deriving them is wrong for the 3-x naming scheme`() {
        // `claude-3-5-sonnet` puts the version BEFORE the family, which the generic deriver renders "3 5 Sonnet".
        assertEquals("Sonnet 3.5", LegacyModels.labelFor("claude-3-5-sonnet"))
        assertEquals("Opus 4.7", LegacyModels.labelFor("claude-opus-4-7"))
        assertNull(LegacyModels.labelFor("opus[1m]"), "a current model is not ours to label")
        assertNull(LegacyModels.labelFor(null))
    }

    @Test
    fun `every id is one the binary actually knows, not one that fits the numbering pattern`() {
        // The guard for the mistake this list invited: `claude-opus-4-2` and `claude-sonnet-4-2` read as
        // perfectly plausible — the numbering has 4.0, 4.1, 4.5, 4.6, 4.7 — and neither has ever existed. An
        // invented id is refused at `set_model`, i.e. it looks like a broken menu entry, not like a typo.
        //
        // Fixture below = every model id in the model tables of `claude` 2.1.223, extracted with the grep in
        // LegacyModels' KDoc (date-suffixed and `-latest`/`-fast`/`-vN` variants folded away). Refresh it from
        // the binary — never by hand — when adding an entry.
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
