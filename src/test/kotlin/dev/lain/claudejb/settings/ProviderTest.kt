package dev.lain.claudejb.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProviderTest {

    @Test
    fun `deepseek with a key emits the base url and api key as a pair`() {
        val env = Provider.launchEnv(Provider.DEEPSEEK, "sk-deepseek-123")
        assertEquals("https://api.deepseek.com/anthropic", env["ANTHROPIC_BASE_URL"])
        assertEquals("sk-deepseek-123", env["ANTHROPIC_API_KEY"])
        assertFalse(env.containsKey("ANTHROPIC_AUTH_TOKEN"))
        assertEquals(setOf("ANTHROPIC_BASE_URL", "ANTHROPIC_API_KEY"), env.keys)
    }

    @Test
    fun `deepseek with a blank or whitespace key emits NOTHING (never a lone base url)`() {
        assertTrue(Provider.launchEnv(Provider.DEEPSEEK, "").isEmpty())
        assertTrue(Provider.launchEnv(Provider.DEEPSEEK, "   ").isEmpty())
        assertTrue(Provider.launchEnv(Provider.DEEPSEEK, null).isEmpty())
    }

    @Test
    fun `anthropic emits nothing even with a key (uses the binary's own login)`() {
        assertTrue(Provider.launchEnv(Provider.ANTHROPIC, "sk-ant-whatever").isEmpty())
    }

    @Test
    fun `the key is trimmed`() {
        assertEquals("k", Provider.launchEnv(Provider.DEEPSEEK, "  k  ")["ANTHROPIC_API_KEY"])
    }

    @Test
    fun `requiresApiKey is true only for third-party providers`() {
        assertFalse(Provider.ANTHROPIC.requiresApiKey)
        assertTrue(Provider.DEEPSEEK.requiresApiKey)
    }

    @Test
    fun `fromId resolves known ids and falls back to the default`() {
        assertEquals(Provider.DEEPSEEK, Provider.fromId("deepseek"))
        assertEquals(Provider.ANTHROPIC, Provider.fromId("anthropic"))
        assertEquals(Provider.DEFAULT, Provider.fromId(null))
        assertEquals(Provider.DEFAULT, Provider.fromId("nonexistent"))
        assertEquals(Provider.ANTHROPIC, Provider.DEFAULT)
    }

    @Test
    fun `looksLikeAnthropicKey recognises the sk-ant- prefix`() {
        assertTrue(Provider.looksLikeAnthropicKey("sk-ant-api03-abc"))
        assertTrue(Provider.looksLikeAnthropicKey("  sk-ant-oat01-xyz  "))
        assertFalse(Provider.looksLikeAnthropicKey("sk-deepseek-123"))
        assertFalse(Provider.looksLikeAnthropicKey(""))
    }
}
