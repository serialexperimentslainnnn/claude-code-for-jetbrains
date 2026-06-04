package dev.lain.claudejb.context

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pure-JVM coverage of [FilePickerHelper.displayName] (the project-root-relative label promoted
 * out of `ExplainSelectionAction.relativize`). The IDE-bound overloads/pickers are exercised by the
 * headless suite; only the string logic is load-bearing here.
 */
class FilePickerHelperTest {

    @Test
    fun `path under basePath is relativized`() {
        assertEquals("src/Foo.kt", FilePickerHelper.displayName("/proj", "/proj/src/Foo.kt"))
    }

    @Test
    fun `path equal to basePath collapses to the base file name`() {
        assertEquals("proj", FilePickerHelper.displayName("/home/me/proj", "/home/me/proj"))
    }

    @Test
    fun `path outside basePath falls back to the file name`() {
        assertEquals("Other.kt", FilePickerHelper.displayName("/proj", "/elsewhere/Other.kt"))
    }

    @Test
    fun `null basePath falls back to the file name`() {
        assertEquals("Bar.kt", FilePickerHelper.displayName(null, "/any/where/Bar.kt"))
    }

    @Test
    fun `trailing-slash basePath is normalized before relativizing`() {
        assertEquals("src/Foo.kt", FilePickerHelper.displayName("/proj/", "/proj/src/Foo.kt"))
    }

    @Test
    fun `a sibling directory sharing the base prefix is not treated as inside`() {
        // "/proj-other/..." must not be relativized against "/proj"
        assertEquals("X.kt", FilePickerHelper.displayName("/proj", "/proj-other/X.kt"))
    }

    @Test
    fun `a path with no separator and outside base is returned as-is`() {
        assertEquals("loose.txt", FilePickerHelper.displayName("/proj", "loose.txt"))
    }
}
