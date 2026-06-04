package dev.lain.claudejb.diff

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Directly exercises [DiffPresenter.isWithinRoot] — the canonicalize-and-prefix containment check used
 * to confine auto-approved Edit/Write/MultiEdit writes (acceptEdits / bypassPermissions) to the project
 * tree. Fail-closed is load-bearing: any null input or IO failure must return false so the broker
 * degrades to a manual permission card rather than silently writing outside the root.
 */
class DiffPresenterIsWithinRootTest {

    @Test
    fun `null path returns false (fail-closed)`(@TempDir root: Path) {
        assertFalse(DiffPresenter.isWithinRoot(null, root.toFile().canonicalPath))
    }

    @Test
    fun `null projectRoot returns false (fail-closed)`(@TempDir root: Path) {
        val file = File(root.toFile(), "a.kt").apply { writeText("") }
        assertFalse(DiffPresenter.isWithinRoot(file.canonicalPath, null))
    }

    @Test
    fun `both null returns false`() {
        assertFalse(DiffPresenter.isWithinRoot(null, null))
    }

    @Test
    fun `file directly under the root is contained`(@TempDir root: Path) {
        val file = File(root.toFile(), "App.kt").apply { writeText("x") }
        assertTrue(DiffPresenter.isWithinRoot(file.canonicalPath, root.toFile().canonicalPath))
    }

    @Test
    fun `nested file under the root is contained`(@TempDir root: Path) {
        val dir = File(root.toFile(), "src/main/kotlin").apply { mkdirs() }
        val file = File(dir, "App.kt").apply { writeText("x") }
        assertTrue(DiffPresenter.isWithinRoot(file.canonicalPath, root.toFile().canonicalPath))
    }

    @Test
    fun `root itself is considered contained`(@TempDir root: Path) {
        val rootPath = root.toFile().canonicalPath
        assertTrue(DiffPresenter.isWithinRoot(rootPath, rootPath))
    }

    @Test
    fun `dotdot traversal that escapes the root is rejected`(@TempDir root: Path) {
        // path is "<root>/../../etc/passwd" — canonicalize() resolves the .. and lands outside root.
        val escaping = File(root.toFile(), "../../etc/passwd").path
        assertFalse(DiffPresenter.isWithinRoot(escaping, root.toFile().canonicalPath))
    }

    @Test
    fun `absolute path outside the root is rejected`(@TempDir root: Path) {
        assertFalse(DiffPresenter.isWithinRoot("/etc/hosts", root.toFile().canonicalPath))
    }

    @Test
    fun `sibling whose name shares the root prefix is rejected`(@TempDir parent: Path) {
        // /tmp/xxx/proj vs /tmp/xxx/proj-evil — naive startsWith() without the path separator would
        // wrongly accept the sibling. The implementation appends File.separator, so it must reject.
        val root = File(parent.toFile(), "proj").apply { mkdirs() }
        val sibling = File(parent.toFile(), "proj-evil").apply { mkdirs() }
        val target = File(sibling, "secret.txt").apply { writeText("x") }
        assertFalse(DiffPresenter.isWithinRoot(target.canonicalPath, root.canonicalPath))
    }

    @Test
    fun `path with spaces and unicode under the root is contained`(@TempDir root: Path) {
        val dir = File(root.toFile(), "señor dir/ünïcode").apply { mkdirs() }
        val file = File(dir, "fïle ñ.txt").apply { writeText("x") }
        assertTrue(DiffPresenter.isWithinRoot(file.canonicalPath, root.toFile().canonicalPath))
    }

    @Test
    fun `symlink pointing outside the root is rejected after canonicalization`(@TempDir tmp: Path) {
        val root = File(tmp.toFile(), "root").apply { mkdirs() }
        val outside = File(tmp.toFile(), "outside").apply { mkdirs() }
        val target = File(outside, "secret.txt").apply { writeText("nope") }
        val link = File(root, "link.txt")
        // Skip cleanly when the platform/test FS forbids symlinks (e.g. some CI sandboxes).
        val created = runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }
        assumeTrue(created.isSuccess, "symlinks unsupported in this environment")
        // canonicalPath resolves the symlink → falls outside root → rejected.
        assertFalse(
            DiffPresenter.isWithinRoot(link.canonicalPath, root.canonicalPath),
            "symlink escaping the root must not be considered contained",
        )
    }

    @Test
    fun `symlink pointing inside the root is accepted`(@TempDir root: Path) {
        val real = File(root.toFile(), "real.txt").apply { writeText("ok") }
        val link = File(root.toFile(), "link.txt")
        val created = runCatching { Files.createSymbolicLink(link.toPath(), real.toPath()) }
        assumeTrue(created.isSuccess, "symlinks unsupported in this environment")
        assertTrue(DiffPresenter.isWithinRoot(link.canonicalPath, root.toFile().canonicalPath))
    }

    @Test
    fun `relative root is canonicalized against the working directory and behaves consistently`(@TempDir root: Path) {
        // The implementation canonicalizes both sides, so a relative root is resolved against user.dir.
        // A file canonicalized from the same absolute base must be classified consistently — either both
        // accepted (when the relative root happens to resolve under the test temp) or both rejected.
        // We pin behavior by canonicalizing the relative root ourselves and comparing outcomes.
        val file = File(root.toFile(), "a.kt").apply { writeText("") }
        val relativeRoot = File(".").path // "."
        val expected = DiffPresenter.isWithinRoot(file.canonicalPath, File(relativeRoot).canonicalPath)
        val actual = DiffPresenter.isWithinRoot(file.canonicalPath, relativeRoot)
        // Implementation canonicalizes internally → the two calls must agree.
        assertTrue(expected == actual, "relative and pre-canonicalized roots must yield the same verdict")
    }
}
