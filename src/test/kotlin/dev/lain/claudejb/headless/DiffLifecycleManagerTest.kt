package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.diff.DiffPresenter
import dev.lain.claudejb.diff.OpenedDiffsService
import dev.lain.claudejb.session.DiffLifecycleManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files

class DiffLifecycleManagerTest : BasePlatformTestCase() {

    private lateinit var manager: DiffLifecycleManager

    override fun setUp() {
        super.setUp()
        manager = DiffLifecycleManager(project)
        OpenedDiffsService.getInstance(project).closeAll()
    }

    private fun tempFile(name: String, content: String): String {
        val dir = Files.createTempDirectory("difflifecycle").toFile()
        val f = File(dir, name)
        f.writeText(content)
        return f.absolutePath
    }

    fun `test captureForReview snapshots pre-write contents`() {
        val path = tempFile("a.kt", "fun a() {}\n")
        val input = buildJsonObject {
            put("file_path", path)
            put("content", "fun b() {}\n")
        }
        val snap = manager.captureForReview("Write", input, "toolu_1")
        assertNotNull(snap)
        assertEquals("Write", snap!!.toolName)
        assertEquals(path, snap.filePath)
        assertEquals("fun a() {}\n", snap.beforeText)
        assertSame(snap, manager.snapshot("toolu_1"))
    }

    fun `test captureForReview returns null without file_path`() {
        val input = buildJsonObject { put("content", "x") }
        assertNull(manager.captureForReview("Write", input, "toolu_x"))
        assertNull(manager.snapshot("toolu_x"))
    }

    fun `test captureForReview captures the snapshot for an auto-approved edit`() {
        val path = tempFile("b.kt", "old\n")
        val input = buildJsonObject {
            put("file_path", path)
            put("content", "new\n")
        }
        val captured = manager.captureForReview("Write", input, "toolu_2")
        assertNotNull(captured)
        val snap = manager.snapshot("toolu_2")
        assertNotNull(snap)
        assertEquals(path, snap!!.filePath)
        assertEquals("old\n", snap.beforeText)
    }

    fun `test onToolResult returns the captured snapshot and does not throw`() {
        val path = tempFile("c.kt", "old\n")
        val input = buildJsonObject {
            put("file_path", path)
            put("content", "new\n")
        }
        manager.captureForReview("Write", input, "toolu_3")

        val snap = manager.onToolResult("toolu_3")
        com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertNotNull(snap)
        assertEquals(path, snap!!.filePath)
    }

    fun `test onToolResult with no snapshot returns null and does not throw`() {
        assertNull(manager.onToolResult("unknown-id"))
    }

    fun `test markForRefresh then refreshTouched does not throw`() {
        val path = tempFile("d.kt", "x\n")
        manager.markForRefresh(path)
        manager.refreshTouched()
        com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        manager.refreshTouched()
    }
}
