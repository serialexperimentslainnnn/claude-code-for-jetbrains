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
        file.writeText("claude's version\n")

        val reseeded = mutableListOf<String>()
        val manager = RollbackManager(project, diffs) { path, _ -> reseeded += path }

        assertTrue(manager.revertEdit(snap!!))
        assertEquals("original\n", file.readText())
        assertEquals(listOf(file.absolutePath), reseeded)
    }

    fun `test an edit outside the project root is refused, not written`() {
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
