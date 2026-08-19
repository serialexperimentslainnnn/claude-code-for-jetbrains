package dev.lain.claudejb.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class LinkGateTest {

    private val home: String get() = System.getProperty("user.home")

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `a file inside the project is openable`() {
        val root = tmp.toFile()
        val f = File(root, "src/Foo.kt").apply {
            parentFile.mkdirs()
            writeText("x")
        }
        assertTrue(LinkResolver.isOpenable(f.path, root.path))
    }

    @Test
    fun `a file inside the user's home is openable even with no project root`() {
        assertTrue(LinkResolver.isOpenable("$home/notes.md", null))
        assertTrue(LinkResolver.isOpenable(home, "/some/unrelated/root"))
    }

    @Test
    fun `system paths are never openable`() {
        val root = tmp.toFile().path
        assertFalse(LinkResolver.isOpenable("/etc/passwd", root))
        assertFalse(LinkResolver.isOpenable("/etc/shadow", root))
        assertFalse(LinkResolver.isOpenable("/usr/bin/env", root))
        assertFalse(LinkResolver.isOpenable("/", root))
    }

    @Test
    fun `another user's home is not openable`() {
        assertFalse(LinkResolver.isOpenable("$home-evil/secrets.txt", tmp.toFile().path))
        assertFalse(LinkResolver.isOpenable("/home/someone-else/.ssh/id_rsa", tmp.toFile().path))
    }

    @Test
    fun `a traversal out of the project is not openable`() {
        val root = File(tmp.toFile(), "proj").apply { mkdirs() }
        assertFalse(LinkResolver.isOpenable("${root.path}/../../../../etc/passwd", root.path))
    }

    @Test
    fun `a symlink planted in the project or home cannot escape the gate`() {
        val root = tmp.toFile()
        val link = File(root, "escape")
        Files.createSymbolicLink(link.toPath(), File("/etc").toPath())
        assertFalse(LinkResolver.isOpenable(File(link, "passwd").path, root.path))
    }

    @Test
    fun `a blank or null path is never openable`() {
        assertFalse(LinkResolver.isOpenable(null, tmp.toFile().path))
        assertFalse(LinkResolver.isOpenable("", tmp.toFile().path))
        assertFalse(LinkResolver.isOpenable("   ", tmp.toFile().path))
    }

    @Test
    fun `expandHome resolves a leading tilde and leaves everything else alone`() {
        assertEquals(File(home, "notes/todo.md").path, LinkResolver.expandHome("~/notes/todo.md"))
        assertEquals(home, LinkResolver.expandHome("~"))
        assertEquals("/etc/passwd", LinkResolver.expandHome("/etc/passwd"))
        assertEquals("src/Foo.kt", LinkResolver.expandHome("src/Foo.kt"))
        assertEquals("~evil/x.kt", LinkResolver.expandHome("~evil/x.kt"))
    }

    @Test
    fun `displayPath is project-relative inside the project and absolute outside it`() {
        assertEquals("src/Foo.kt", LinkResolver.displayPath("/p/src/Foo.kt", "/p"))
        assertEquals("$home/notes.md", LinkResolver.displayPath("$home/notes.md", "/p"))
        assertEquals("/p/src/Foo.kt", LinkResolver.displayPath("/p/src/Foo.kt", null))
    }

    private fun touch(rel: String): File =
        File(tmp.toFile(), rel).apply {
            parentFile.mkdirs()
            writeText("x")
        }

    @Test
    fun `scanForNames finds a bare name inside an excluded build directory`() {
        touch("build/distributions/app-4.3.0.zip")
        val found = LinkResolver.scanForNames(tmp.toFile().path, listOf("app-4.3.0.zip" to null))
        assertEquals(1, found.size)
        assertEquals("app-4.3.0.zip", found[0].token)
        assertEquals("build/distributions/app-4.3.0.zip", found[0].path)
    }

    @Test
    fun `scanForNames keeps the line suffix in the token`() {
        touch("build/gen/Report.kt")
        val found = LinkResolver.scanForNames(tmp.toFile().path, listOf("Report.kt" to 42))
        assertEquals("Report.kt:42", found.single().token)
        assertEquals(42, found.single().line)
    }

    @Test
    fun `scanForNames refuses an ambiguous name`() {
        touch("build/a/dup.txt")
        touch("build/b/dup.txt")
        assertTrue(LinkResolver.scanForNames(tmp.toFile().path, listOf("dup.txt" to null)).isEmpty())
    }

    @Test
    fun `scanForNames never descends into node_modules or dot-directories`() {
        touch("node_modules/pkg/hidden.js")
        touch(".git/objects/buried.txt")
        val names = listOf("hidden.js" to null, "buried.txt" to null)
        assertTrue(LinkResolver.scanForNames(tmp.toFile().path, names).isEmpty())
    }

    @Test
    fun `scanForNames is a no-op without a root or without names`() {
        assertTrue(LinkResolver.scanForNames(null, listOf("x.kt" to null)).isEmpty())
        assertTrue(LinkResolver.scanForNames(tmp.toFile().path, emptyList()).isEmpty())
    }

    @Test
    fun `a path href is recognised, a URL href is not`() {
        assertTrue(LinkResolver.isFilePathHref("docs/BACKLOG.md"))
        assertTrue(LinkResolver.isFilePathHref("/etc/hosts"))
        assertTrue(LinkResolver.isFilePathHref("~/notes.md"))
        assertTrue(LinkResolver.isFilePathHref("src/main/kotlin/A.kt"))

        assertFalse(LinkResolver.isFilePathHref("https://example.com"))
        assertFalse(LinkResolver.isFilePathHref("jb://open?file=x"))
        assertFalse(LinkResolver.isFilePathHref("mailto:someone@example.invalid"))
        assertFalse(LinkResolver.isFilePathHref("tel:+34000000000"))
        assertFalse(LinkResolver.isFilePathHref("sms:+34000000000"))
        assertFalse(LinkResolver.isFilePathHref("data:image/png;base64,AAAA"))
    }

    @Test
    fun `a Windows drive letter is a path, not a scheme`() {
        assertTrue(LinkResolver.isFilePathHref("""C:\src\main.kt"""))
        assertTrue(LinkResolver.isFilePathHref("D:/work/notes.md"))
        assertFalse(LinkResolver.isFilePathHref(""))
    }
}
