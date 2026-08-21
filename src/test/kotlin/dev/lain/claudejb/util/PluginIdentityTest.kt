package dev.lain.claudejb.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class PluginIdentityTest {

    @Test
    fun `the plugin names itself, and never a browser`() {
        assertTrue(PluginIdentity.USER_AGENT.startsWith("ClaudeCodeNative/")) { PluginIdentity.USER_AGENT }
        assertTrue(PluginIdentity.PLUGIN_VERSION in PluginIdentity.USER_AGENT) { PluginIdentity.USER_AGENT }
        assertFalse("Mozilla" in PluginIdentity.USER_AGENT) {
            "A browser User-Agent buys nothing a server asks for, ages into a fingerprint, and an invalid " +
                "one earns a 403 a client would report as a permission problem. Name the plugin instead."
        }
    }

    @Test
    fun `the advertised version is the one the build ships`() {
        val declared = DECLARED_VERSION.find(File("build.gradle.kts").readText())?.groupValues?.get(1)

        assertEquals(
            declared,
            PluginIdentity.PLUGIN_VERSION,
            "PluginIdentity.PLUGIN_VERSION drifted from `version` in build.gradle.kts, so every outbound " +
                "request announces a version this plugin is not. The descriptor cannot be read at runtime " +
                "without an internal API this build treats as a verification failure, so this test is what " +
                "keeps the constant honest.",
        )
    }

    private companion object {

        val DECLARED_VERSION = Regex("""^version\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
    }
}
