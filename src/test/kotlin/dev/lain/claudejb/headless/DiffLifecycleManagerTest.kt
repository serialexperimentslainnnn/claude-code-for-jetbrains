package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.diff.DiffPresenter
import dev.lain.claudejb.diff.OpenedDiffsService
import dev.lain.claudejb.session.DiffLifecycleManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files

/**
 * Headless: [DiffLifecycleManager] owns snapshot capture, the auto-opened diff registry and the write-safe
 * VFS refresh for one session. Uses the light-fixture [project] so [DiffPresenter.openDiff] (FileEditorManager)
 * has a real project to open the diff tab in.
 */
class DiffLifecycleManagerTest : BasePlatformTestCase() {

    private lateinit var manager: DiffLifecycleManager

    override fun setUp() {
        super.setUp()
        manager = DiffLifecycleManager(project)
        OpenedDiffsService.getInstance(project).closeAll()
    }

    /** Writes a temp file under the test root and returns its absolute path. */
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
        // Persisted under the id for the transcript's "View diff".
        assertSame(snap, manager.snapshot("toolu_1"))
    }

    fun `test captureForReview returns null without file_path`() {
        val input = buildJsonObject { put("content", "x") }
        assertNull(manager.captureForReview("Write", input, "toolu_x"))
        assertNull(manager.snapshot("toolu_x"))
    }

    // NOTE: we exercise the manager's logic via captureForReview/onToolResult (synchronous, UI-free), NOT
    // autoOpenDiff — the latter calls DiffPresenter.openDiff → FileEditorManager.openFile(ChainDiffVirtualFile),
    // which the light BasePlatformTestCase fixture (TestEditorManagerImpl) cannot open (NPE). Opening the real
    // diff tab is DiffPresenter's responsibility (covered by its own tests + runtime); here we assert the
    // load-bearing behaviour of the auto-approve path: the pre-write snapshot is captured and retrievable.
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

        // No auto-opened diff tab was registered (we didn't call autoOpenDiff), so onToolResult just returns the
        // persisted pre-write snapshot for the inline diff and must not throw.
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
        // refreshTouched dispatches a write-safe (nonModal) VFS refresh — drain and ensure it runs cleanly.
        com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        // Calling again with nothing pending is a no-op.
        manager.refreshTouched()
    }
}
