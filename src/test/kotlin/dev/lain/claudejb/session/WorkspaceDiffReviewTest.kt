package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * [WorkspaceDiffReview] — rebuilding the BASE side of a whole-session diff.
 *
 * The binary sends hunks, the IDE's diff viewer needs two whole texts, and the left-hand one has to be
 * reconstructed. The property that matters most here is the refusal: a review tool that shows a fabricated
 * "before" is worse than one that shows nothing, because nothing on screen tells the user which it is.
 */
class WorkspaceDiffReviewTest {

    private fun hunk(oldStart: Int, oldLines: Int, newStart: Int, newLines: Int, vararg lines: String) =
        WorkspaceDiff.Hunk(oldStart, oldLines, newStart, newLines, lines.toList())

    private fun stats(path: String, binary: Boolean = false, untracked: Boolean = false) =
        WorkspaceDiff.FileStats(path = path, added = 1, removed = 1, isBinary = binary, isUntracked = untracked)

    @Test
    fun `a modified line is walked back to what it was`() {
        val current = "one\nTWO\nthree"
        val base = WorkspaceDiffReview.baseOf(current, listOf(hunk(1, 3, 1, 3, " one", "-two", "+TWO", " three")))
        assertEquals("one\ntwo\nthree", base)
    }

    @Test
    fun `an added line disappears and a removed line comes back`() {
        assertEquals("a\nb", WorkspaceDiffReview.baseOf("a\nNEW\nb", listOf(hunk(1, 2, 1, 3, " a", "+NEW", " b"))))
        assertEquals("a\ngone\nb", WorkspaceDiffReview.baseOf("a\nb", listOf(hunk(1, 3, 1, 2, " a", "-gone", " b"))))
    }

    @Test
    fun `several hunks in one file all land`() {
        // Applied bottom-up, or the first replacement shifts the line numbers of the second.
        val current = "A\n2\n3\n4\nB"
        val base = WorkspaceDiffReview.baseOf(
            current,
            listOf(hunk(1, 1, 1, 1, "-a", "+A"), hunk(5, 1, 5, 1, "-b", "+B")),
        )
        assertEquals("a\n2\n3\n4\nb", base)
    }

    // ── the refusal ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a hunk that does not match the file on disk reconstructs nothing`() {
        // The file moved between the diff being computed and being read — ordinary on a tree an agent is
        // still editing. Guessing here would put a base on screen that never existed.
        val hunks = listOf(hunk(1, 3, 1, 3, " one", "-two", "+TWO", " three"))
        assertNull(WorkspaceDiffReview.baseOf("one\nSOMETHING ELSE\nthree", hunks))
    }

    @Test
    fun `a hunk pointing past the end of the file reconstructs nothing`() {
        assertNull(WorkspaceDiffReview.baseOf("short", listOf(hunk(40, 2, 40, 2, " x", "+y"))))
        assertNull(WorkspaceDiffReview.baseOf("short", listOf(hunk(1, 1, 0, 1, "+y"))))
    }

    // ── what each kind of file is allowed to show ─────────────────────────────────────────────────────────

    @Test
    fun `each reason produces the side it should`() {
        val diff = WorkspaceDiff(
            perFileStats = listOf(
                stats("edited.kt"),
                stats("new.kt", untracked = true),
                stats("logo.png", binary = true),
                stats("huge.json"),
                stats("moved.kt"),
            ),
            hunks = listOf(
                WorkspaceDiff.FileHunks("edited.kt", listOf(hunk(1, 1, 1, 1, "-was", "+is"))),
                WorkspaceDiff.FileHunks("moved.kt", listOf(hunk(1, 1, 1, 1, "-was", "+is"))),
            ),
            skippedLarge = listOf("huge.json"),
        )
        val disk = mapOf(
            "edited.kt" to "is",
            "new.kt" to "brand new",
            "logo.png" to "PNG",
            "huge.json" to "{}",
            "moved.kt" to "something else entirely",
        )
        val byPath = WorkspaceDiffReview.sides(diff) { disk[it] }.associateBy { it.path }

        assertEquals(WorkspaceDiffReview.Reason.OK, byPath["edited.kt"]!!.reason)
        assertEquals("was", byPath["edited.kt"]!!.base)
        // Untracked is not "unknown": the whole file IS the addition, so the empty left side is the truth.
        assertEquals(WorkspaceDiffReview.Reason.UNTRACKED, byPath["new.kt"]!!.reason)
        assertEquals("", byPath["new.kt"]!!.base)
        assertEquals(WorkspaceDiffReview.Reason.BINARY, byPath["logo.png"]!!.reason)
        assertEquals(WorkspaceDiffReview.Reason.WITHHELD, byPath["huge.json"]!!.reason)
        assertEquals(WorkspaceDiffReview.Reason.DIVERGED, byPath["moved.kt"]!!.reason)
        listOf("logo.png", "huge.json", "moved.kt").forEach { assertNull(byPath[it]!!.base, it) }
    }

    @Test
    fun `a file that cannot be read is skipped, not shown as empty`() {
        // An empty pane reads as "the whole file was deleted", which is a different and alarming claim.
        val diff = WorkspaceDiff(perFileStats = listOf(stats("gone.kt")))
        assertEquals(emptyList<WorkspaceDiffReview.Side>(), WorkspaceDiffReview.sides(diff) { null })
    }

    @Test
    fun `every reason names itself in the left-hand pane`() {
        val labels = WorkspaceDiffReview.Reason.entries.map { reason ->
            WorkspaceDiffReview.baseLabel(WorkspaceDiffReview.Side("f", null, "", reason), "HEAD")
        }
        assertEquals(labels.size, labels.toSet().size, "each reason must be distinguishable on screen")
        assertEquals("HEAD", labels.first(), "OK uses the base the binary reported")
    }
}
