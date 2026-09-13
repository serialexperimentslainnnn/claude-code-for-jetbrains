package dev.lain.claudejb.model.settings.env

import com.intellij.openapi.util.SystemInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class EnvScriptLoaderTest {

    @TempDir
    lateinit var tmp: Path

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
    fun `a blank or missing script loads nothing`() {
        assertEquals(emptyMap<String, String>(), EnvScriptLoader.load(null))
        assertEquals(emptyMap<String, String>(), EnvScriptLoader.load("  "))
        assertEquals(emptyMap<String, String>(), EnvScriptLoader.load(tmp.resolve("missing.sh").toString()))
    }

    @Test
    fun `a real script is sourced and its exports come back`() {
        assumeFalse(SystemInfo.isWindows)
        val script = tmp.resolve("env.sh")
        Files.writeString(script, "export CLAUDE_TEST_SOURCED=hello\n")
        assertEquals("hello", EnvScriptLoader.load(script.toString())["CLAUDE_TEST_SOURCED"])
    }

    @Test
    fun `a script that exits before the shell can dump its environment yields nothing`() {
        assumeFalse(SystemInfo.isWindows)
        val failing = tmp.resolve("fail.sh")
        Files.writeString(failing, "export CLAUDE_TEST_PARTIAL=yes\nexit 3\n")
        assertEquals(emptyMap<String, String>(), EnvScriptLoader.load(failing.toString()))
    }

    @Test
    fun `line starting with '=' is ignored (empty key)`() {
        val env = EnvScriptLoader.parse("=novalue")
        assertNull(env["="])
        assertEquals(emptyMap<String, String>(), env)
    }
}
