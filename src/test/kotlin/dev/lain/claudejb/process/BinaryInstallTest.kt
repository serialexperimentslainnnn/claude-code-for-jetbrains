package dev.lain.claudejb.process

import com.intellij.openapi.util.SystemInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class BinaryInstallTest {

    @Test
    fun `methods exist for this OS, with unique ids and complete fields`() {
        val methods = BinaryInstall.methods()
        assertTrue("at least one install route per OS", methods.isNotEmpty())
        assertEquals("ids must be unique", methods.size, methods.map { it.id }.toSet().size)
        methods.forEach { m ->
            assertTrue("label: ${m.id}", m.label.isNotBlank())
            assertTrue("display: ${m.id}", m.display.isNotBlank())
            assertTrue("argv: ${m.id}", m.argv.isNotEmpty())
            assertTrue("shell: ${m.id}", m.shell.isNotBlank())
        }
    }

    @Test
    fun `method resolves by id and unknown ids yield null`() {
        val first = BinaryInstall.methods().first()
        assertEquals(first, BinaryInstall.method(first.id))
        assertEquals(null, BinaryInstall.method("no-such-method"))
    }

    @Test
    fun `blank and nonexistent paths are invalid`() {
        assertTrue(BinaryInstall.validate("") is BinaryInstall.Validation.Invalid)
        assertTrue(BinaryInstall.validate("   ") is BinaryInstall.Validation.Invalid)
        assertTrue(BinaryInstall.validate("/definitely/not/here/claude") is BinaryInstall.Validation.Invalid)
    }

    @Test
    fun `a directory without a claude executable inside is invalid`() {
        val dir = createTempDirectory("bi-empty").toFile()
        try {
            assertTrue(BinaryInstall.validate(dir.absolutePath) is BinaryInstall.Validation.Invalid)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an executable that is not claude is rejected by the version probe`() {
        assumeTrue(!SystemInfo.isWindows)
        val fake = script("#!/bin/sh\necho \"totally-not-claude 1.0\"\n")
        try {
            val verdict = BinaryInstall.validate(fake.absolutePath)
            assertTrue("expected Invalid, got $verdict", verdict is BinaryInstall.Validation.Invalid)
        } finally {
            fake.parentFile.deleteRecursively()
        }
    }

    @Test
    fun `a binary that identifies as Claude Code passes, from a file or its directory`() {
        assumeTrue(!SystemInfo.isWindows)
        val fake = script("#!/bin/sh\necho \"9.9.9 (Claude Code)\"\n", name = "claude")
        try {
            val byFile = BinaryInstall.validate(fake.absolutePath)
            assertTrue("by file: $byFile", byFile is BinaryInstall.Validation.Ok)
            assertTrue((byFile as BinaryInstall.Validation.Ok).version.contains("Claude Code"))
            val byDir = BinaryInstall.validate(fake.parentFile.absolutePath)
            assertTrue("by dir: $byDir", byDir is BinaryInstall.Validation.Ok)
        } finally {
            fake.parentFile.deleteRecursively()
        }
    }

    @Test
    fun `a non-executable file is invalid before any probe runs`() {
        assumeTrue(!SystemInfo.isWindows)
        val dir = createTempDirectory("bi-noexec").toFile()
        val file = File(dir, "claude").apply { writeText("#!/bin/sh\necho hi\n") }
        try {
            assertTrue(BinaryInstall.validate(file.absolutePath) is BinaryInstall.Validation.Invalid)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun script(body: String, name: String = "fake-bin"): File {
        val dir = createTempDirectory("bi-exec").toFile()
        return File(dir, name).apply {
            writeText(body)
            setExecutable(true)
        }
    }
}
