package dev.lain.claudejb.headless

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.diff.OpenedDiffsService

/**
 * Headless: [OpenedDiffsService] closes the diff tabs the plugin opened — **and only those**.
 *
 * It used to assert on an `openCount()` the service exposed for the tool window's *Close diffs* action. That
 * action is gone and so is the count, and a test whose only subject is a number kept alive for the test is not
 * coverage. What the service is actually for is [OpenedDiffsService.closeAll] — the safety net behind
 * `DiffTabCleanup`, for the auto-approved writes whose diffs no permission card ever resolves — so that is what
 * is driven here, against real editors rather than against a set.
 */
class OpenedDiffsServiceHeadlessTest : BasePlatformTestCase() {

    fun `test getInstance returns the project service`() {
        val service = OpenedDiffsService.getInstance(project)
        assertNotNull(service)
        // Same project → same service instance.
        assertSame(service, OpenedDiffsService.getInstance(project))
    }

    fun `test closeAll closes the registered tabs and leaves the others open`() {
        val service = OpenedDiffsService.getInstance(project)
        val manager = FileEditorManager.getInstance(project)
        val ours = myFixture.addFileToProject("ours.txt", "diff").virtualFile
        // The user's own tab: the whole point of tracking what WE opened is that this one survives.
        val theirs = myFixture.addFileToProject("theirs.txt", "mine").virtualFile

        manager.openFile(ours, false)
        manager.openFile(theirs, false)
        service.register(ours)
        assertTrue(manager.isFileOpen(ours))
        assertTrue(manager.isFileOpen(theirs))

        service.closeAll()

        assertFalse("the diff the plugin opened must be closed", manager.isFileOpen(ours))
        assertTrue("a tab the plugin never opened must be left alone", manager.isFileOpen(theirs))
    }

    fun `test an unregistered tab is no longer closed`() {
        val service = OpenedDiffsService.getInstance(project)
        val manager = FileEditorManager.getInstance(project)
        val file = myFixture.addFileToProject("reopened.txt", "x").virtualFile
        manager.openFile(file, false)

        // `DiffPresenter.closeDiff` unregisters as it closes, so a tab the user reopened afterwards is theirs.
        service.register(file)
        service.unregister(file)
        service.closeAll()

        assertTrue("unregister must take the file out of the registry", manager.isFileOpen(file))
    }

    /** The light fixture reuses the project across methods, so each test leaves the editor as it found it. */
    override fun tearDown() {
        try {
            val manager = FileEditorManager.getInstance(project)
            manager.openFiles.forEach { manager.closeFile(it) }
            OpenedDiffsService.getInstance(project).closeAll()
        } finally {
            super.tearDown()
        }
    }
}
