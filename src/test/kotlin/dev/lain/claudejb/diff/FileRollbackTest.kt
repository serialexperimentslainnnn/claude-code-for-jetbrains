package dev.lain.claudejb.diff

import dev.lain.claudejb.diff.FileRollback.EditRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [FileRollback]'s pure ordering helpers — the logic that drives the diff-history panel's
 * "newer edits" warning and the newest-first "roll back all" traversal. The IDE-side [FileRollback.revert]
 * (path-confined VFS write) is exercised by a headless test, not here.
 */
class FileRollbackTest {

    // index 0..4: a.kt edited 3x (0,2,4), b.kt once (1), c.kt once (3)
    private val edits = listOf(
        EditRef(0, "a.kt"),
        EditRef(1, "b.kt"),
        EditRef(2, "a.kt"),
        EditRef(3, "c.kt"),
        EditRef(4, "a.kt"),
    )

    @Test
    fun `hasNewerEditToSameFile is true when a later edit touches the same file`() {
        assertTrue(FileRollback.hasNewerEditToSameFile(edits, EditRef(0, "a.kt")))
        assertTrue(FileRollback.hasNewerEditToSameFile(edits, EditRef(2, "a.kt")))
    }

    @Test
    fun `hasNewerEditToSameFile is false for the newest edit to a file`() {
        assertFalse(FileRollback.hasNewerEditToSameFile(edits, EditRef(4, "a.kt")))
        assertFalse(FileRollback.hasNewerEditToSameFile(edits, EditRef(1, "b.kt")))
        assertFalse(FileRollback.hasNewerEditToSameFile(edits, EditRef(3, "c.kt")))
    }

    @Test
    fun `hasNewerEditToSameFile ignores later edits to other files`() {
        // c.kt (index 3) comes after b.kt (index 1) but is a different path.
        assertFalse(FileRollback.hasNewerEditToSameFile(edits, EditRef(1, "b.kt")))
    }

    @Test
    fun `oldestPerFile keeps the lowest index per path in first-appearance order`() {
        val oldest = FileRollback.oldestPerFile(edits)
        assertEquals(listOf("a.kt", "b.kt", "c.kt"), oldest.map { it.filePath })
        assertEquals(listOf(0, 1, 3), oldest.map { it.index })
    }

    @Test
    fun `oldestPerFile picks the original snapshot even when the oldest edit is not first`() {
        val reordered = listOf(
            EditRef(0, "a.kt"),
            EditRef(1, "b.kt"),
            EditRef(2, "b.kt"),
        )
        val oldest = FileRollback.oldestPerFile(reordered).associate { it.filePath to it.index }
        assertEquals(0, oldest["a.kt"])
        assertEquals(1, oldest["b.kt"])
    }

    @Test
    fun `rollbackAllOrder returns oldest-per-file newest-first`() {
        val order = FileRollback.rollbackAllOrder(edits)
        // oldest-per-file indices are 0 (a), 1 (b), 3 (c); reversed by index → 3, 1, 0.
        assertEquals(listOf(3, 1, 0), order.map { it.index })
        assertEquals(listOf("c.kt", "b.kt", "a.kt"), order.map { it.filePath })
    }

    @Test
    fun `helpers are empty-safe`() {
        assertTrue(FileRollback.oldestPerFile(emptyList()).isEmpty())
        assertTrue(FileRollback.rollbackAllOrder(emptyList()).isEmpty())
        assertFalse(FileRollback.hasNewerEditToSameFile(emptyList(), EditRef(0, "a.kt")))
    }
}
