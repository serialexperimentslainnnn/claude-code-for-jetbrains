package dev.lain.claudejb.headless

import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.diff.OpenedDiffsService

/** Headless: the [OpenedDiffsService] project service tracks the diff tabs the plugin opened. */
class OpenedDiffsServiceHeadlessTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The light-fixture project (and its services) is reused across methods; start each test clean.
        OpenedDiffsService.getInstance(project).closeAll()
    }

    fun `test getInstance returns the project service`() {
        val service = OpenedDiffsService.getInstance(project)
        assertNotNull(service)
        // Same project → same service instance.
        assertSame(service, OpenedDiffsService.getInstance(project))
    }

    fun `test register increments open count`() {
        val service = OpenedDiffsService.getInstance(project)
        assertEquals(0, service.openCount())
        service.register(LightVirtualFile("a.txt", "x"))
        assertEquals(1, service.openCount())
    }

    fun `test register is idempotent`() {
        val service = OpenedDiffsService.getInstance(project)
        val file = LightVirtualFile("a.txt", "x")
        service.register(file)
        service.register(file)
        assertEquals(1, service.openCount())
    }

    fun `test unregister decrements open count`() {
        val service = OpenedDiffsService.getInstance(project)
        val file = LightVirtualFile("a.txt", "x")
        service.register(file)
        assertEquals(1, service.openCount())
        service.unregister(file)
        assertEquals(0, service.openCount())
    }

    fun `test closeAll empties the registry without throwing`() {
        val service = OpenedDiffsService.getInstance(project)
        service.register(LightVirtualFile("a.txt", "x"))
        service.register(LightVirtualFile("b.txt", "y"))
        assertEquals(2, service.openCount())
        // The LightVirtualFiles are not open in FileEditorManager, so closeAll just clears the set.
        service.closeAll()
        assertEquals(0, service.openCount())
    }
}
