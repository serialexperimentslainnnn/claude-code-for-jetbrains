package dev.lain.claudejb.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The security gate behind jump-to-code ([LinkResolver.isOpenable]) plus the `~` expansion and path display that
 * feed it. A link may point **inside the project or inside the user's own home, and nowhere else** — this is what
 * stands between a hostile model output and `/etc/shadow`, so it is tested as a boundary, escapes included.
 *
 * Note this is deliberately WIDER than the write gate (`DiffPresenter.isWithinRoot` against the project root),
 * which still confines what the binary may write. Opening is a user-clicked read; writing is not.
 */
class LinkGateTest {

    private val home: String get() = System.getProperty("user.home")

    @TempDir
    lateinit var tmp: Path

    // ── isOpenable ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a file inside the project is openable`() {
        val root = tmp.toFile()
        val f = File(root, "src/Foo.kt").apply { parentFile.mkdirs(); writeText("x") }
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
        // A sibling directory that merely shares the home's prefix must not pass the prefix check.
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
        // Canonicalisation is what defeats this: the link's target, not its name, is what gets checked.
        Files.createSymbolicLink(link.toPath(), File("/etc").toPath())
        assertFalse(LinkResolver.isOpenable(File(link, "passwd").path, root.path))
    }

    @Test
    fun `a blank or null path is never openable`() {
        assertFalse(LinkResolver.isOpenable(null, tmp.toFile().path))
        assertFalse(LinkResolver.isOpenable("", tmp.toFile().path))
        assertFalse(LinkResolver.isOpenable("   ", tmp.toFile().path))
    }

    // ── expandHome ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `expandHome resolves a leading tilde and leaves everything else alone`() {
        assertEquals(File(home, "notes/todo.md").path, LinkResolver.expandHome("~/notes/todo.md"))
        assertEquals(home, LinkResolver.expandHome("~"))
        assertEquals("/etc/passwd", LinkResolver.expandHome("/etc/passwd"))
        assertEquals("src/Foo.kt", LinkResolver.expandHome("src/Foo.kt"))
        // Not a home reference: `~evil` is another user's home in shell syntax, and we do not expand it.
        assertEquals("~evil/x.kt", LinkResolver.expandHome("~evil/x.kt"))
    }

    // ── displayPath ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `displayPath is project-relative inside the project and absolute outside it`() {
        assertEquals("src/Foo.kt", LinkResolver.displayPath("/p/src/Foo.kt", "/p"))
        assertEquals("$home/notes.md", LinkResolver.displayPath("$home/notes.md", "/p"))
        assertEquals("/p/src/Foo.kt", LinkResolver.displayPath("/p/src/Foo.kt", null))
    }

    // ── scanForNames (the on-disk fallback for names no index knows: excluded dirs like build/) ───────────

    private fun touch(rel: String): File =
        File(tmp.toFile(), rel).apply { parentFile.mkdirs(); writeText("x") }

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

    /** Same rule as symbols: a wrong jump is worse than no link. */
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
}
