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
 * Headless: [RollbackManager.revertEdit] is what a transcript card's **Restore** falls back to when the binary
 * cannot rewind, and it writes to the user's disk — so the two things pinned here are that it restores the
 * captured contents *and* reseeds the binary's read state, and that it refuses a path outside the project.
 *
 * Both are silent failures if they regress: a Restore that reports success and leaves the file as Claude wrote
 * it, or a rollback that overwrites a file the user never opened this project to touch.
 *
 * Uses the light fixture's real [project] because the write gate is resolved against the project root.
 */
class RollbackManagerHeadlessTest : BasePlatformTestCase() {

    private lateinit var transcript: TranscriptModel
    private lateinit var diffs: DiffLifecycleManager
    private lateinit var rollback: RollbackManager

    override fun setUp() {
        super.setUp()
        transcript = TranscriptModel()
        diffs = DiffLifecycleManager(project)
        rollback = RollbackManager(project, diffs) { _, _ -> }
    }

    private fun tempFile(name: String, content: String): String {
        val f = File(Files.createTempDirectory("rollback").toFile(), name)
        f.writeText(content)
        return f.absolutePath
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
            buildJsonObject {
                put("file_path", file.absolutePath)
                put("content", "claude's version\n")
            },
            id,
        )
        assertNotNull(snap)
        // The binary wrote after the snapshot was taken; that is the state the user asks to undo.
        file.writeText("claude's version\n")

        // The read-state reseed is what stops the binary's NEXT Edit from validating against the pre-rollback
        // contents, so it is part of the contract, not a side effect.
        val reseeded = mutableListOf<String>()
        val manager = RollbackManager(project, diffs) { path, _ -> reseeded += path }

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
            buildJsonObject {
                put("file_path", outside)
                put("content", "x")
            },
            id,
        )
        assertFalse(rollback.revertEdit(snap!!))
        assertEquals("untouched\n", File(outside).readText())
    }
}
