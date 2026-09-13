package dev.lain.claudejb.model.diff

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile

class DiffPresenterReadCurrentTest {

    @TempDir
    lateinit var root: File

    @Test
    fun `a file inside the project is read as it is`() {
        val file = File(root, "src/App.kt").apply {
            parentFile.mkdirs()
            writeText("fun main() {}\n")
        }

        assertEquals("fun main() {}\n", DiffPresenter.readCurrent(file.path, root.path))
    }

    @Test
    fun `a path the tool names outside the project is refused, not read`() {
        val outside = File(root.parentFile, "outside-${root.name}.txt").apply { writeText("secret") }
        try {
            assertNull(DiffPresenter.readCurrent(outside.path, root.path))
            assertNull(DiffPresenter.readCurrent(File(root, "../${outside.name}").path, root.path))
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `a file that does not exist yet diffs against nothing`() {
        assertEquals("", DiffPresenter.readCurrent(File(root, "new.kt").path, root.path))
    }

    @Test
    fun `a file over the cap is refused before a byte is read`() {
        val big = File(root, "big.bin")
        RandomAccessFile(big, "rw").use { it.setLength(DiffPresenter.MAX_DIFF_FILE_BYTES + 1) }

        assertNull(DiffPresenter.readCurrent(big.path, root.path))
    }

    @Test
    fun `without a project root nothing is readable`() {
        val file = File(root, "a.txt").apply { writeText("x") }

        assertNull(DiffPresenter.readCurrent(file.path, null))
    }
}
