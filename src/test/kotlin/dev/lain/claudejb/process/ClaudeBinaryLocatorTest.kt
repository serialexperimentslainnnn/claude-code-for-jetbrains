package dev.lain.claudejb.process

import com.intellij.openapi.util.SystemInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Exercises [ClaudeBinaryLocator.locate] focusing on the [override] branch (PATH-independent, safe to
 * unit-test) and the [resolveNodeScript] Windows shim parser. The PATH-search and typicalDirs branches
 * read the live process env / user home, so they're only smoke-asserted (we don't mutate the test
 * process's PATH) — covered in the headless / integration layer.
 */
class ClaudeBinaryLocatorTest {

    private fun makeExecutable(dir: File, name: String, body: String = "#!/bin/sh\necho fake\n"): File {
        val f = File(dir, name)
        f.writeText(body)
        f.setExecutable(true, false)
        return f
    }

    @Test
    fun `override pointing to an executable file is returned verbatim`(@TempDir tmp: Path) {
        assumeFalse(SystemInfo.isWindows, "POSIX exec bit semantics")
        val exe = makeExecutable(tmp.toFile(), "claude")
        val located = ClaudeBinaryLocator.locate(exe.absolutePath)
        assertNotNull(located)
        assertEquals(exe.canonicalPath, located!!.canonicalPath)
    }

    @Test
    fun `override pointing to a non-existent path falls through to auto-detection`(@TempDir tmp: Path) {
        // The override only "wins" when its file exists and is executable; otherwise locate() falls
        // through to PATH / typicalDirs. We can't assert the exact fallback (depends on the host), but
        // the call must NOT return the bogus override.
        val bogus = File(tmp.toFile(), "does-not-exist").absolutePath
        val located = ClaudeBinaryLocator.locate(bogus)
        // Either null (no system claude) or some real file, but never the bogus path.
        if (located != null) {
            assert(located.absolutePath != bogus) { "stale override must not be returned" }
            assert(located.canExecute()) { "fallback result must be executable" }
        }
    }

    @Test
    fun `override pointing to a non-executable file is rejected`(@TempDir tmp: Path) {
        assumeFalse(SystemInfo.isWindows, "POSIX exec bit semantics; Windows has no exec bit")
        val notExe = File(tmp.toFile(), "claude").apply { writeText("plain"); setExecutable(false, false) }
        val located = ClaudeBinaryLocator.locate(notExe.absolutePath)
        // Falls through (host claude or null) — never returns the non-executable override.
        if (located != null) {
            assert(located.canonicalPath != notExe.canonicalPath) {
                "non-executable override must not be returned as the located binary"
            }
        }
    }

    @Test
    fun `blank override is treated as no override`(@TempDir tmp: Path) {
        // Should not throw; behaviour matches `locate(null)`.
        val a = ClaudeBinaryLocator.locate("")
        val b = ClaudeBinaryLocator.locate(null)
        // Both call the same auto-detection path — either both null or both the same canonical file.
        when {
            a == null && b == null -> Unit
            a != null && b != null -> assertEquals(a.canonicalPath, b.canonicalPath)
            else -> error("blank override and null override must agree (got a=$a, b=$b)")
        }
    }

    @Test
    fun `override pointing to a directory is rejected (not a regular file)`(@TempDir tmp: Path) {
        val dir = File(tmp.toFile(), "claude-dir").apply { mkdirs() }
        val located = ClaudeBinaryLocator.locate(dir.absolutePath)
        // Falls through; must not return the directory.
        if (located != null) {
            assert(located.canonicalPath != dir.canonicalPath)
        }
    }

    // --- resolveNodeScript (Windows-only behaviour) ---------------------------------------------------

    @Test
    fun `resolveNodeScript returns null on non-Windows regardless of input`(@TempDir tmp: Path) {
        assumeFalse(SystemInfo.isWindows)
        val cmd = File(tmp.toFile(), "claude.cmd").apply { writeText("@echo off\n") }
        assertNull(ClaudeBinaryLocator.resolveNodeScript(cmd))
    }

    @Test
    fun `resolveNodeScript finds cli_js next to the cmd shim on Windows`(@TempDir tmp: Path) {
        assumeTrue(SystemInfo.isWindows, "Windows-only npm shim layout")
        // npm global layout: <prefix>\claude.cmd  +  <prefix>\node_modules\@anthropic-ai\claude-code\cli.js
        val prefix = tmp.toFile()
        val cli = File(prefix, "node_modules\\@anthropic-ai\\claude-code\\cli.js").apply {
            parentFile.mkdirs(); writeText("// fake cli\n")
        }
        val cmd = File(prefix, "claude.cmd").apply {
            writeText("@\"%~dp0\\node_modules\\@anthropic-ai\\claude-code\\cli.js\" %*\n")
        }
        val resolved = ClaudeBinaryLocator.resolveNodeScript(cmd)
        assertNotNull(resolved)
        assertEquals(cli.canonicalPath, resolved!!.canonicalPath)
    }

    @Test
    fun `resolveNodeScript falls back to regex extraction when the standard layout is absent`(@TempDir tmp: Path) {
        assumeTrue(SystemInfo.isWindows, "Windows-only npm shim layout")
        val prefix = tmp.toFile()
        // Non-standard install: cli.js lives in `tools\\` instead of node_modules\\…
        val cli = File(prefix, "tools\\custom-cli.js").apply { parentFile.mkdirs(); writeText("// fake\n") }
        val cmd = File(prefix, "claude.cmd").apply {
            writeText("@node \"%~dp0\\tools\\custom-cli.js\" %*\n")
        }
        val resolved = ClaudeBinaryLocator.resolveNodeScript(cmd)
        assertNotNull(resolved)
        assertEquals(cli.canonicalPath, resolved!!.canonicalPath)
    }
}
