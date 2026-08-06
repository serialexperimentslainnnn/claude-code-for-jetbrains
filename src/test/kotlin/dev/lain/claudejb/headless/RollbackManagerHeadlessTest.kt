package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.session.DiffLifecycleManager
import dev.lain.claudejb.session.RollbackManager
import dev.lain.claudejb.session.Speaker
import dev.lain.claudejb.session.TranscriptModel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files

/**
 * Headless: [RollbackManager.reviewableEdits] is what the Diff History panel lists, and it is a JOIN of two
 * independent sources — the transcript's TOOL rows and the pre-write snapshots [DiffLifecycleManager] holds.
 * A row with no snapshot cannot be reverted (there is nothing to restore), so it must be dropped rather than
 * offered; a non-file tool must never appear at all. Both are silent failures if they regress: the panel would
 * simply show a Restore button that cannot work.
 *
 * Uses the light fixture's real [project] because the display path is resolved against the project root.
 */
class RollbackManagerHeadlessTest : BasePlatformTestCase() {

    private lateinit var transcript: TranscriptModel
    private lateinit var diffs: DiffLifecycleManager
    private lateinit var rollback: RollbackManager

    override fun setUp() {
        super.setUp()
        transcript = TranscriptModel()
        diffs = DiffLifecycleManager(project)
        rollback = RollbackManager(project, transcript, diffs) { _, _ -> }
    }

    private fun tempFile(name: String, content: String): String {
        val f = File(Files.createTempDirectory("rollback").toFile(), name)
        f.writeText(content)
        return f.absolutePath
    }

    /** Adds a TOOL row for [tool] and, unless [snapshot] is false, captures its pre-write snapshot. */
    private fun edit(tool: String, id: String, path: String, snapshot: Boolean = true) {
        transcript.add(Speaker.TOOL, tool, meta = tool, toolUseId = id)
        if (snapshot) {
            diffs.captureForReview(tool, buildJsonObject { put("file_path", path); put("content", "new") }, id)
        }
    }

    fun `test reviewable edits are listed in transcript order`() {
        val first = tempFile("a.kt", "a")
        val second = tempFile("b.kt", "b")
        edit("Write", "toolu_1", first)
        edit("Edit", "toolu_2", second)

        val edits = rollback.reviewableEdits()
        assertEquals(listOf("toolu_1", "toolu_2"), edits.map { it.toolUseId })
        assertEquals("a", edits[0].snapshot.beforeText)
        assertEquals(second, edits[1].snapshot.filePath)
    }

    fun `test an edit whose snapshot was never captured is not offered`() {
        edit("Write", "toolu_missing", tempFile("c.kt", "c"), snapshot = false)
        assertTrue(rollback.reviewableEdits().isEmpty())
    }

    fun `test non-file tools never appear, even with a captured snapshot`() {
        val path = tempFile("d.kt", "d")
        // Bash is not in DiffPresenter.REVIEWABLE_TOOLS: there is no captured before-state to restore.
        edit("Bash", "toolu_bash", path)
        assertTrue(rollback.reviewableEdits().isEmpty())
    }

    fun `test reverting an edit restores the captured contents and reseeds the read state`() {
        // The light fixture's project root is an in-memory path with nothing behind it, and `FileRollback`
        // writes through `LocalFileSystem` — so the root has to exist on disk for a real revert to be possible.
        val root = File(project.basePath!!).apply { mkdirs() }
        val file = File(root, "reverted.kt")
        file.writeText("original\n")
        val id = "toolu_revert"
        transcript.add(Speaker.TOOL, "Edit", meta = "Edit", toolUseId = id)
        val snap = diffs.captureForReview(
            "Edit",
            buildJsonObject { put("file_path", file.absolutePath); put("content", "claude's version\n") },
            id,
        )
        assertNotNull(snap)
        // The binary wrote after the snapshot was taken; that is the state the user asks to undo.
        file.writeText("claude's version\n")

        // The read-state reseed is what stops the binary's NEXT Edit from validating against the pre-rollback
        // contents, so it is part of the contract, not a side effect.
        val reseeded = mutableListOf<String>()
        val manager = RollbackManager(project, transcript, diffs) { path, _ -> reseeded += path }

        assertTrue(manager.revertEdit(snap!!))
        assertEquals("original\n", file.readText())
        assertEquals(listOf(file.absolutePath), reseeded)
    }

    fun `test an edit outside the project root is refused, not written`() {
        // The write gate is project-only on purpose: a transcript can name any path, and a rollback must never
        // restore stale contents over a file outside the tree the user opened.
        val outside = tempFile("outside.kt", "untouched\n")
        val id = "toolu_outside"
        transcript.add(Speaker.TOOL, "Write", meta = "Write", toolUseId = id)
        val snap = diffs.captureForReview(
            "Write",
            buildJsonObject { put("file_path", outside); put("content", "x") },
            id,
        )
        assertFalse(rollback.revertEdit(snap!!))
        assertEquals("untouched\n", File(outside).readText())
    }

    fun `test the display path is project-relative for files inside the root`() {
        val inside = File(project.basePath!!, "inside.kt")
        val id = "toolu_inside"
        transcript.add(Speaker.TOOL, "Write", meta = "Write", toolUseId = id)
        diffs.captureForReview(
            "Write",
            buildJsonObject { put("file_path", inside.absolutePath); put("content", "x") },
            id,
        )
        assertEquals("inside.kt", rollback.reviewableEdits().single().displayPath)
    }
}
