package dev.lain.claudejb.context

import dev.lain.claudejb.diff.DiffPresenter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class ProjectTreeTest {

    private companion object {

        val NUL: Char = 0.toChar()

        fun dir(path: String) = ProjectTree.Entry(path.substringAfterLast('/'), path, true)

        fun file(path: String) = ProjectTree.Entry(path.substringAfterLast('/'), path, false)
    }

    private class DeclaredTree(private val children: Map<String, List<ProjectTree.Entry>>) {
        val asked = mutableListOf<String>()

        fun provider(): (ProjectTree.Entry) -> List<ProjectTree.Entry> = { entry ->
            asked += entry.path
            children[entry.path].orEmpty()
        }
    }

    @Test
    fun `the root itself resolves`(@TempDir root: Path) {
        val resolved = ProjectTree.resolve(root.toFile().canonicalPath, "")
        assertNotNull(resolved)
        assertEquals(root.toFile().canonicalPath, resolved!!.canonicalPath)
    }

    @Test
    fun `a nested relative path resolves inside the root`(@TempDir root: Path) {
        File(root.toFile(), "src/main").mkdirs()
        val resolved = ProjectTree.resolve(root.toFile().canonicalPath, "src/main")
        assertNotNull(resolved)
        assertTrue(DiffPresenter.isWithinRoot(resolved!!.path, root.toFile().canonicalPath))
    }

    @Test
    fun `a dotdot segment cannot climb out of the root`(@TempDir parent: Path) {
        val root = File(parent.toFile(), "proj").apply { mkdirs() }
        File(parent.toFile(), "secret.txt").writeText("nope")
        assertNull(ProjectTree.resolve(root.canonicalPath, "../secret.txt"))
        assertNull(ProjectTree.resolve(root.canonicalPath, "src/../../secret.txt"))
    }

    @Test
    fun `a sibling directory sharing the root's name prefix is not inside it`(@TempDir parent: Path) {
        val root = File(parent.toFile(), "proj").apply { mkdirs() }
        File(parent.toFile(), "proj-evil").mkdirs()
        assertNull(ProjectTree.resolve(root.canonicalPath, "../proj-evil/secret.txt"))
    }

    @Test
    fun `an absolute path either folds into the root or is refused`(@TempDir root: Path) {
        val canonicalRoot = root.toFile().canonicalPath
        val resolved = ProjectTree.resolve(canonicalRoot, "/etc/passwd")
        assertTrue(resolved == null || DiffPresenter.isWithinRoot(resolved.path, canonicalRoot)) {
            "an absolute input escaped the root: ${resolved?.path}"
        }
    }

    @Test
    fun `a symlink whose target is outside the root is refused`(@TempDir tmp: Path) {
        val root = File(tmp.toFile(), "root").apply { mkdirs() }
        val outside = File(tmp.toFile(), "outside").apply { mkdirs() }
        File(outside, "secret.txt").writeText("nope")
        val created = runCatching { Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath()) }
        assumeTrue(created.isSuccess, "symlinks unsupported in this environment")

        assertNull(ProjectTree.resolve(root.canonicalPath, "link"))
        assertNull(ProjectTree.resolve(root.canonicalPath, "link/secret.txt"))
    }

    @Test
    fun `a symlink whose target is inside the root is allowed`(@TempDir root: Path) {
        val real = File(root.toFile(), "real").apply { mkdirs() }
        val created = runCatching { Files.createSymbolicLink(File(root.toFile(), "link").toPath(), real.toPath()) }
        assumeTrue(created.isSuccess, "symlinks unsupported in this environment")

        assertNotNull(ProjectTree.resolve(root.toFile().canonicalPath, "link"))
    }

    @Test
    fun `no root means nothing resolves`() {
        assertNull(ProjectTree.resolve(null, "src"))
        assertNull(ProjectTree.resolve("   ", "src"))
    }

    @Test
    fun `a path that cannot be canonicalized is refused`(@TempDir root: Path) {
        val canonicalRoot = root.toFile().canonicalPath
        assumeTrue(
            runCatching { File(canonicalRoot, "a${NUL}b").canonicalFile }.isFailure,
            "this platform does not reject a NUL byte in a path",
        )
        assertNull(ProjectTree.resolve(canonicalRoot, "a${NUL}b"))
    }

    @Test
    fun `no hostile spelling escapes the root`(@TempDir root: Path) {
        val canonicalRoot = root.toFile().canonicalPath
        val hostile = listOf(
            "..",
            "../..",
            "../../etc/passwd",
            "./../x",
            "a/b/../../../..",
            "/etc/passwd",
            "//etc/passwd",
            "~/.ssh/id_rsa",
            "\\..\\..\\Windows",
            "a/".repeat(2000) + "deep.txt",
            "x".repeat(100_000),
        )
        for (candidate in hostile) {
            val resolved = ProjectTree.resolve(canonicalRoot, candidate)
            assertTrue(resolved == null || DiffPresenter.isWithinRoot(resolved.path, canonicalRoot)) {
                "'$candidate' resolved outside the root as ${resolved?.path}"
            }
        }
    }

    @Test
    fun `directories come first, then files, both case-insensitively alphabetical`() {
        val ordered = ProjectTree.ordered(
            listOf(file("b.txt"), dir("Zebra"), file("Apple.txt"), dir("alpha"), file("apple.txt")),
        )
        assertEquals(listOf("alpha", "Zebra", "Apple.txt", "apple.txt", "b.txt"), ordered.map { it.name })
    }

    @Test
    fun `the order does not depend on the order the entries arrived in`() {
        val entries = listOf(dir("src"), file("README.md"), dir("Docs"), file("build.gradle.kts"), file("a.md"))
        assertEquals(
            ProjectTree.ordered(entries).map { it.path },
            ProjectTree.ordered(entries.reversed()).map { it.path },
        )
    }

    @Test
    fun `a text file within the size ceiling is attachable`() {
        assertTrue(ProjectTree.isAttachableFile("notes.md", 4_096, binary = false))
        assertTrue(ProjectTree.isAttachableFile("notes.md", ImageAttachments.MAX_IMAGE_BYTES.toLong(), false))
    }

    @Test
    fun `a file over the size ceiling is not attachable, text or not`() {
        val tooBig = ImageAttachments.MAX_IMAGE_BYTES.toLong() + 1
        assertFalse(ProjectTree.isAttachableFile("huge.md", tooBig, binary = false))
        assertFalse(ProjectTree.isAttachableFile("huge.png", tooBig, binary = true))
    }

    @Test
    fun `a binary is refused unless it is an image a turn can carry`() {
        assertFalse(ProjectTree.isAttachableFile("app.jar", 1_024, binary = true))
        assertTrue(ProjectTree.isAttachableFile("shot.png", 1_024, binary = true))
        assertTrue(ProjectTree.isAttachableFile("SHOT.PNG", 1_024, binary = true))
    }

    @Test
    fun `an unrecognised extension is judged by size alone`() {
        assertTrue(ProjectTree.isAttachableFile("config.envrc", 512, binary = false))
        assertTrue(ProjectTree.isAttachableFile("Makefile", 512, binary = false))
    }

    private fun sampleTree() = DeclaredTree(
        mapOf(
            "" to listOf(dir("src"), file("README.md")),
            "src" to listOf(dir("src/main"), file("src/App.kt")),
            "src/main" to listOf(file("src/main/M.kt")),
        ),
    )

    @Test
    fun `the files mode collects every file at and below the marked folder`() {
        val expansion = ProjectTree.walk(dir(""), ProjectTree.Mode.FILES, sampleTree().provider())

        assertEquals(listOf("README.md", "src/App.kt", "src/main/M.kt"), expansion.paths)
        assertFalse(expansion.truncated)
    }

    @Test
    fun `the directories mode collects the marked folder and its subfolders, and nothing else`() {
        val expansion = ProjectTree.walk(dir(""), ProjectTree.Mode.DIRECTORIES, sampleTree().provider())

        assertEquals(listOf("", "src", "src/main"), expansion.paths)
        assertFalse(expansion.truncated)
    }

    @Test
    fun `the two modes disagree about the same folder`() {
        val files = ProjectTree.walk(dir("src"), ProjectTree.Mode.FILES, sampleTree().provider()).paths
        val dirs = ProjectTree.walk(dir("src"), ProjectTree.Mode.DIRECTORIES, sampleTree().provider()).paths

        assertEquals(listOf("src/App.kt", "src/main/M.kt"), files)
        assertEquals(listOf("src", "src/main"), dirs)
    }

    @Test
    fun `an excluded directory is invisible to the expansion, even though it exists on disk`(@TempDir root: Path) {
        val onDisk = root.toFile()
        File(onDisk, "build").mkdirs()
        File(onDisk, "build/generated.txt").writeText("machine output")
        File(onDisk, "src").mkdirs()
        File(onDisk, "src/App.kt").writeText("fun main() {}")

        val start = ProjectTree.Entry(onDisk.name, onDisk.path, true)
        val declared = DeclaredTree(
            mapOf(
                onDisk.path to listOf(dir("${onDisk.path}/src")),
                "${onDisk.path}/src" to listOf(file("${onDisk.path}/src/App.kt")),
            ),
        )

        val expansion = ProjectTree.walk(start, ProjectTree.Mode.FILES, declared.provider())

        assertEquals(listOf("${onDisk.path}/src/App.kt"), expansion.paths)
        assertTrue(expansion.paths.none { "build" in it || "generated" in it }) {
            "the walk went behind the index's back to the filesystem: ${expansion.paths}"
        }
    }

    @Test
    fun `the listing shows exactly what was declared, in order`() {
        val declared = sampleTree()
        val rows = ProjectTree.ordered(declared.provider()(dir("")))

        assertEquals(listOf("src", "README.md"), rows.map { it.path })
    }

    @Test
    fun `an expansion under the ceiling is not reported as truncated`() {
        val exactly = (1..ProjectTree.MAX_ENTRIES).map { file("f$it.txt") }
        val expansion = ProjectTree.walk(dir(""), ProjectTree.Mode.FILES, DeclaredTree(mapOf("" to exactly)).provider())

        assertEquals(ProjectTree.MAX_ENTRIES, expansion.paths.size)
        assertFalse(expansion.truncated)
    }

    @Test
    fun `going past the ceiling is reported, not silently trimmed`() {
        val tooMany = (1..ProjectTree.MAX_ENTRIES + 1).map { file("f$it.txt") }
        val expansion = ProjectTree.walk(dir(""), ProjectTree.Mode.FILES, DeclaredTree(mapOf("" to tooMany)).provider())

        assertEquals(ProjectTree.MAX_ENTRIES, expansion.paths.size)
        assertTrue(expansion.truncated) {
            "a folder that attaches a slice of itself and says nothing is worse than one that refuses"
        }
    }

    @Test
    fun `the ceiling stops the walk instead of truncating a finished one`() {
        val declared = DeclaredTree(
            mapOf(
                "" to (1..ProjectTree.MAX_ENTRIES + 1).map { file("f$it.txt") } + dir("later"),
                "later" to listOf(file("later/one.txt")),
            ),
        )

        val expansion = ProjectTree.walk(dir(""), ProjectTree.Mode.FILES, declared.provider())

        assertTrue(expansion.truncated)
        assertEquals(listOf(""), declared.asked) {
            "the walk kept going after it had its answer; it asked about ${declared.asked}"
        }
    }

    @Test
    fun `a tree of empty directories terminates and says it was cut short`() {
        val deep = HashMap<String, List<ProjectTree.Entry>>()
        deep[""] = listOf(dir("d0"))
        repeat(25_000) { step -> deep["d$step"] = listOf(dir("d${step + 1}")) }

        val expansion = ProjectTree.walk(dir(""), ProjectTree.Mode.FILES, DeclaredTree(deep).provider())

        assertTrue(expansion.paths.isEmpty())
        assertTrue(expansion.truncated)
    }
}
