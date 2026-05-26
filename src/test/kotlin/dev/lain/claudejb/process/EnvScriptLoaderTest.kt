package dev.lain.claudejb.process

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Unit tests for [EnvScriptLoader.parse]. Same package so the `internal` function is reachable. */
class EnvScriptLoaderTest {

    @Test
    fun `simple KEY=VALUE line`() {
        val env = EnvScriptLoader.parse("FOO=bar")
        assertEquals("bar", env["FOO"])
    }

    @Test
    fun `value containing '=' keeps everything after the first '='`() {
        val env = EnvScriptLoader.parse("PATH=a=b")
        assertEquals("a=b", env["PATH"])
    }

    @Test
    fun `line without '=' is ignored`() {
        val env = EnvScriptLoader.parse("no_equals_here")
        assertEquals(emptyMap<String, String>(), env)
    }

    @Test
    fun `line whose KEY has whitespace is ignored`() {
        val env = EnvScriptLoader.parse("BAD KEY=value")
        assertFalse(env.containsKey("BAD KEY"))
        assertEquals(emptyMap<String, String>(), env)
    }

    @Test
    fun `multiple lines are all parsed`() {
        val env = EnvScriptLoader.parse(
            """
            FOO=bar
            BAZ=qux
            PATH=/usr/bin:/bin
            """.trimIndent(),
        )
        assertEquals("bar", env["FOO"])
        assertEquals("qux", env["BAZ"])
        assertEquals("/usr/bin:/bin", env["PATH"])
        assertEquals(3, env.size)
    }

    @Test
    fun `empty lines are ignored`() {
        val env = EnvScriptLoader.parse("\nFOO=bar\n\nBAZ=qux\n")
        assertEquals("bar", env["FOO"])
        assertEquals("qux", env["BAZ"])
        assertEquals(2, env.size)
    }

    @Test
    fun `empty value is allowed`() {
        val env = EnvScriptLoader.parse("EMPTY=")
        assertEquals("", env["EMPTY"])
    }

    @Test
    fun `line starting with '=' is ignored (empty key)`() {
        val env = EnvScriptLoader.parse("=novalue")
        assertNull(env["="])
        assertEquals(emptyMap<String, String>(), env)
    }
}
