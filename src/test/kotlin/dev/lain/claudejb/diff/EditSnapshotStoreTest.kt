package dev.lain.claudejb.diff

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Unit tests for [EditSnapshotStore]: the pre-write capture that makes a transcript edit re-diffable later.
 * The key invariant is the regression guard — the captured `beforeText` plus [DiffPresenter.proposedContent]
 * must reproduce exactly what a live (pre-write) review would have shown, so re-opening the diff after the
 * binary has overwritten the file still diffs old→new rather than new→new (an empty diff).
 */
class EditSnapshotStoreTest {

    @Test
    fun `capture stores the file's current contents before the write`(@TempDir dir: Path) {
        val file = File(dir.toFile(), "a.kt").apply { writeText("before") }
        val store = EditSnapshotStore()
        val input = buildJsonObject {
            put("file_path", file.path)
            put("old_string", "before")
            put("new_string", "after")
        }

        val snap = store.capture("Edit", input, "tool-1")

        assertEquals("before", snap?.beforeText)
        assertEquals("Edit", snap?.toolName)
        assertEquals(file.path, snap?.filePath)
        assertEquals(snap, store.get("tool-1"))
    }

    @Test
    fun `capture of a not-yet-existing file yields empty beforeText`(@TempDir dir: Path) {
        val store = EditSnapshotStore()
        val input = buildJsonObject {
            put("file_path", File(dir.toFile(), "new.kt").path)
            put("content", "fresh")
        }

        assertEquals("", store.capture("Write", input, "tool-2")?.beforeText)
    }

    @Test
    fun `capture without file_path returns null and stores nothing`() {
        val store = EditSnapshotStore()
        val input = buildJsonObject { put("content", "x") }

        assertNull(store.capture("Write", input, "tool-3"))
        assertNull(store.get("tool-3"))
    }

    @Test
    fun `get of an unknown id returns null`() {
        assertNull(EditSnapshotStore().get("nope"))
    }

    @Test
    fun `snapshot plus proposedContent reproduces the live pre-write diff`(@TempDir dir: Path) {
        // Regression guard for the "empty diff after write" bug: capture, then overwrite the file as the binary
        // would, then verify the persisted snapshot still yields the correct old→new pair.
        val file = File(dir.toFile(), "a.kt").apply { writeText("foo foo") }
        val store = EditSnapshotStore()
        val input = buildJsonObject {
            put("file_path", file.path)
            put("old_string", "foo")
            put("new_string", "bar")
        }

        val snap = store.capture("Edit", input, "tool-4")!!
        file.writeText("bar foo") // binary performs the write after we answered allow

        // The diff must reconstruct from the captured snapshot, NOT the now-overwritten disk contents.
        assertEquals("foo foo", snap.beforeText, "snapshot must keep the original pre-write contents")
        assertEquals("bar foo", DiffPresenter.proposedContent(snap.toolName, snap.input, snap.beforeText))
    }
}
