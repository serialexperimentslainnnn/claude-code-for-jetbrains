package dev.lain.claudejb.model.settings

import dev.lain.claudejb.model.protocol.models.ModelInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LaunchDefaultsTest {

    private fun m(value: String) = ModelInfo(value)

    @Test
    fun `pins the concrete Opus when the binary offers it`() {
        val catalog = listOf(m("default"), m("opus[1m]"), m("sonnet"), m("haiku"))
        assertEquals("opus[1m]", LaunchDefaults.preferredDefault(catalog))
    }

    @Test
    fun `before the handshake, with no catalog, returns the pin optimistically`() {
        assertEquals("opus[1m]", LaunchDefaults.preferredDefault(emptyList()))
    }

    @Test
    fun `falls back to the binary's recommended alias when the pin is absent`() {
        val catalog = listOf(m("default"), m("sonnet"), m("haiku"))
        assertEquals("default", LaunchDefaults.preferredDefault(catalog))
    }

    @Test
    fun `falls back to the first listed model when neither the pin nor the alias exists`() {
        val catalog = listOf(m("sonnet"), m("haiku"))
        assertEquals("sonnet", LaunchDefaults.preferredDefault(catalog))
    }

    @Test
    fun `the recommended alias is a distinct constant from the pinned default`() {
        assertEquals("default", LaunchDefaults.RECOMMENDED_ALIAS)
        assertEquals("opus[1m]", LaunchDefaults.DEFAULT_MODEL)
    }
}
