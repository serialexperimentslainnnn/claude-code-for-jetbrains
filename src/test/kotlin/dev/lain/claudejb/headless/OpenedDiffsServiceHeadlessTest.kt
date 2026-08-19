package dev.lain.claudejb.headless

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.diff.OpenedDiffsService

class OpenedDiffsServiceHeadlessTest : BasePlatformTestCase() {

    fun `test getInstance returns the project service`() {
        val service = OpenedDiffsService.getInstance(project)
        assertNotNull(service)
        assertSame(service, OpenedDiffsService.getInstance(project))
    }

    fun `test closeAll closes the registered tabs and leaves the others open`() {
        val service = OpenedDiffsService.getInstance(project)
        val manager = FileEditorManager.getInstance(project)
        val ours = myFixture.addFileToProject("ours.txt", "diff").virtualFile
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

        service.register(file)
        service.unregister(file)
        service.closeAll()

        assertTrue("unregister must take the file out of the registry", manager.isFileOpen(file))
    }

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
