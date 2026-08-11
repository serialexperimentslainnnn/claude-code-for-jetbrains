package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * [LiveOutputTail] — "what is new since I last looked", which is what makes polling a growing file affordable.
 */
class LiveOutputTailTest {

    @TempDir
    lateinit var dir: Path

    private fun append(file: Path, text: String) {
        Files.writeString(file, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    @Test
    fun `only the new bytes come back`() {
        val file = dir.resolve("agent.out")
        val tail = LiveOutputTail()
        append(file, "first\n")
        assertEquals("first\n", tail.readNew(file))
        assertEquals("", tail.readNew(file))
        append(file, "second\n")
        assertEquals("second\n", tail.readNew(file))
    }

    @Test
    fun `a file that shrank is read from the start again`() {
        val file = dir.resolve("agent.out")
        val tail = LiveOutputTail()
        append(file, "aaaa\n")
        tail.readNew(file)
        // Rotation or a rewrite: continuing from the old offset would hand back a fragment of the new
        // content starting mid-line, which reads as corruption.
        Files.writeString(file, "b\n")
        assertEquals("b\n", tail.readNew(file))
    }

    @Test
    fun `a missing file, a directory or an unreadable path is not an error`() {
        val tail = LiveOutputTail()
        assertEquals("", tail.readNew(dir.resolve("does-not-exist")))
        assertEquals("", tail.readNew(dir))
    }

    @Test
    fun `a huge burst is truncated to its tail rather than pushed whole`() {
        val file = dir.resolve("big.out")
        val tail = LiveOutputTail()
        // 300 KB in one go: a build log between two polls. The view is of a running thing, not an archive.
        append(file, "x".repeat(300_000) + "END")
        val read = tail.readNew(file)
        assertTrue(read.length < 300_000, "expected a truncated chunk, got ${read.length}")
        assertTrue(read.endsWith("END"), "the TAIL is what matters, not the head")
    }

    @Test
    fun `forgetting a path rewinds it`() {
        val file = dir.resolve("agent.out")
        val tail = LiveOutputTail()
        append(file, "hello\n")
        tail.readNew(file)
        tail.forget(file)
        assertEquals("hello\n", tail.readNew(file))
    }
}
