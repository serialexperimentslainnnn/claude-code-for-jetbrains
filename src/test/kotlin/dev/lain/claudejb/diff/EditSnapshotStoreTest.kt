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

    /**
     * A blank `tool_use_id` must NOT be indexed. Every blank id is the same map key, so storing them would
     * make each pathless edit overwrite the previous one and hand the transcript's "View diff" a snapshot
     * belonging to a different edit. The snapshot is still RETURNED, because the transient auto-approve
     * diff is opened from the return value rather than from the map.
     */
    @Test
    fun `capture with a blank tool_use_id returns the snapshot without indexing it`(@TempDir dir: Path) {
        val file = File(dir.toFile(), "a.kt").apply { writeText("before") }
        val store = EditSnapshotStore()
        val input = buildJsonObject { put("file_path", file.path) }

        val snap = store.capture("Edit", input, "")

        assertEquals("before", snap?.beforeText, "the caller still needs the snapshot to open its diff")
        assertNull(store.get(""), "a blank id must never become a map key")
    }

    /**
     * `updateInput` is what makes the transcript show what was ACTUALLY written: when the user tweaks the
     * proposed side of the review diff, the approved input is re-encoded and the snapshot repointed at it,
     * while the captured before-text — the only copy of the pre-write file — must survive untouched.
     * Nothing exercised this, so a change that dropped the before-text would have been silent.
     */
    @Test
    fun `updateInput repoints the snapshot at what was written and keeps the before-text`(@TempDir dir: Path) {
        val file = File(dir.toFile(), "a.kt").apply { writeText("original") }
        val store = EditSnapshotStore()
        val proposed = buildJsonObject {
            put("file_path", file.path)
            put("old_string", "original")
            put("new_string", "claude's version")
        }
        store.capture("Edit", proposed, "tool-5")

        val userEdited = buildJsonObject {
            put("file_path", file.path)
            put("old_string", "original")
            put("new_string", "the user's version")
        }
        store.updateInput("tool-5", userEdited)

        val snap = store.get("tool-5")!!
        assertEquals("original", snap.beforeText, "the pre-write capture must not be lost")
        assertEquals(userEdited, snap.input)
        assertEquals("the user's version", DiffPresenter.proposedContent(snap.toolName, snap.input, snap.beforeText))
    }

    @Test
    fun `updateInput is a no-op for a blank or unknown id`(@TempDir dir: Path) {
        val store = EditSnapshotStore()
        val input = buildJsonObject { put("file_path", File(dir.toFile(), "a.kt").path) }
        // Neither call may create an entry: updateInput repoints an existing snapshot, it never captures one.
        store.updateInput("", input)
        store.updateInput("never-captured", input)
        assertNull(store.get(""))
        assertNull(store.get("never-captured"))
    }
}
