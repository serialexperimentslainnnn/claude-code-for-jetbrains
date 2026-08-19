package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.ModelInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ModelSelectionTest {

    private fun m(value: String) = ModelInfo(value)

    @Test
    fun `pins the concrete Opus when the binary offers it`() {
        val catalog = listOf(m("default"), m("opus[1m]"), m("sonnet"), m("haiku"))
        assertEquals("opus[1m]", ClaudeSession.preferredDefault(catalog))
    }

    @Test
    fun `before the handshake, with no catalog, returns the pin optimistically`() {
        assertEquals("opus[1m]", ClaudeSession.preferredDefault(emptyList()))
    }

    @Test
    fun `falls back to the binary's recommended alias when the pin is absent`() {
        val catalog = listOf(m("default"), m("sonnet"), m("haiku"))
        assertEquals("default", ClaudeSession.preferredDefault(catalog))
    }

    @Test
    fun `falls back to the first listed model when neither the pin nor the alias exists`() {
        val catalog = listOf(m("sonnet"), m("haiku"))
        assertEquals("sonnet", ClaudeSession.preferredDefault(catalog))
    }

    @Test
    fun `the recommended alias is a distinct constant from the pinned default`() {
        assertEquals("default", ClaudeSession.RECOMMENDED_ALIAS)
        assertEquals("opus[1m]", ClaudeSession.DEFAULT_MODEL)
    }
}
