package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.protocol.ModelInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The model-label logic ([JcefState.modelDisplayLabel] / [JcefState.deriveModelLabel]) — pure, so tested directly.
 * The point of these labels is that the model's VERSION is visible (the binary's `displayName` omits it; the
 * version lives in `description`), and that nothing is hardcoded — a stale "Opus 4.8" literal was exactly the bug.
 */
class JcefModelLabelTest {

    // The real catalog the binary reports (claude 2.1.220): displayName has no version, description leads with it.
    private fun opus() = ModelInfo("opus[1m]", "Opus (1M context)", "Opus 5 with 1M context · Best for everyday, complex tasks")
    private fun sonnet() = ModelInfo("sonnet", "Sonnet", "Sonnet 5 · Efficient for routine tasks")
    private fun haiku() = ModelInfo("haiku", "Haiku", "Haiku 4.5 · Fastest for quick answers")

    @Test
    fun `label prefers the description head so the version is shown`() {
        assertEquals("Opus 5 with 1M context", JcefState.modelDisplayLabel(opus()))
        assertEquals("Sonnet 5", JcefState.modelDisplayLabel(sonnet()))
        assertEquals("Haiku 4.5", JcefState.modelDisplayLabel(haiku()))
    }

    @Test
    fun `label falls back to displayName when there is no description`() {
        assertEquals("Sonnet", JcefState.modelDisplayLabel(ModelInfo("sonnet", "Sonnet", "")))
    }

    @Test
    fun `label falls back to the derived id when neither description nor displayName is present`() {
        assertEquals("Opus 4.8", JcefState.modelDisplayLabel(ModelInfo("claude-opus-4-8", "", "")))
    }

    @Test
    fun `deriveModelLabel splits family and version from a canonical id`() {
        assertEquals("Opus 4.8", JcefState.deriveModelLabel("claude-opus-4-8"))
        assertEquals("Sonnet 5", JcefState.deriveModelLabel("claude-sonnet-5"))
    }

    @Test
    fun `deriveModelLabel strips an alias bracket suffix — no version to invent`() {
        assertEquals("Opus", JcefState.deriveModelLabel("opus[1m]"))
        assertEquals("Sonnet", JcefState.deriveModelLabel("sonnet"))
    }

    @Test
    fun `deriveModelLabel never emits a hardcoded default version`() {
        // The old code returned "Default · Opus 4.8" for a blank/"default" id — a literal that goes stale. It must not.
        assertEquals("Claude", JcefState.deriveModelLabel(""))
        assertEquals("Default", JcefState.deriveModelLabel("default"))
    }
}
