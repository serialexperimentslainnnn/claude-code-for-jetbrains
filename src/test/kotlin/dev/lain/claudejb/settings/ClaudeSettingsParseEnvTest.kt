package dev.lain.claudejb.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ClaudeSettings.parseEnv]. Exercises the `KEY=VALUE` line parsing directly via the
 * plain no-arg constructor (no IDE/service runtime needed); the env source is the persisted state.
 */
class ClaudeSettingsParseEnvTest {

    private fun settingsWithEnv(env: String): ClaudeSettings =
        ClaudeSettings().also { it.getState().envVars = env }

    @Test
    fun `parses simple KEY=VALUE`() {
        val env = settingsWithEnv("FOO=bar").parseEnv()
        assertEquals(mapOf("FOO" to "bar"), env)
    }

    @Test
    fun `ignores comment lines starting with hash`() {
        val env = settingsWithEnv("# a comment\nFOO=bar").parseEnv()
        assertEquals(mapOf("FOO" to "bar"), env)
    }

    @Test
    fun `ignores blank lines`() {
        val env = settingsWithEnv("\n   \nFOO=bar\n\n").parseEnv()
        assertEquals(mapOf("FOO" to "bar"), env)
    }

    @Test
    fun `trims whitespace around key and value`() {
        val env = settingsWithEnv("  FOO  =   bar  ").parseEnv()
        assertEquals(mapOf("FOO" to "bar"), env)
    }

    @Test
    fun `keeps everything after first equals when value contains equals`() {
        val env = settingsWithEnv("URL=https://x/?a=1&b=2").parseEnv()
        assertEquals(mapOf("URL" to "https://x/?a=1&b=2"), env)
    }

    @Test
    fun `ignores lines without an equals sign`() {
        val env = settingsWithEnv("NOT_AN_ASSIGNMENT\nFOO=bar").parseEnv()
        assertEquals(mapOf("FOO" to "bar"), env)
    }

    @Test
    fun `ignores lines with an empty key`() {
        val env = settingsWithEnv("=orphan\n   =alsoOrphan\nFOO=bar").parseEnv()
        assertEquals(mapOf("FOO" to "bar"), env)
    }

    @Test
    fun `parses multiple entries`() {
        val env = settingsWithEnv("A=1\nB=2\n# c\nC=3").parseEnv()
        assertEquals(mapOf("A" to "1", "B" to "2", "C" to "3"), env)
    }

    @Test
    fun `empty env yields empty map`() {
        assertTrue(settingsWithEnv("").parseEnv().isEmpty())
    }
}
