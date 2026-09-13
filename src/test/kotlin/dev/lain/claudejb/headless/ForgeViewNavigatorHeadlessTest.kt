package dev.lain.claudejb.headless

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.content.ContentFactory
import dev.lain.claudejb.controller.git.ForgeViewNavigator
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel

class ForgeViewNavigatorHeadlessTest : BasePlatformTestCase() {

    class Row(val number: Long, val title: String) {
        override fun toString() = "#$number $title"
    }

    private lateinit var list: JList<Any>

    private fun registerView() {
        val model = DefaultListModel<Any>().apply {
            addElement(Row(41, "first"))
            addElement("not a request")
            addElement(Row(76, "release"))
        }
        list = JList(model)
        val window = ToolWindowManager.getInstance(project).registerToolWindow(ForgeViewNavigator.TOOL_WINDOW_IDS.first()) {
            anchor = ToolWindowAnchor.LEFT
        }
        val panel = JPanel().apply { add(list) }
        window.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
    }

    fun `test without the view nothing opens and nothing selects`() {
        assertFalse(ForgeViewNavigator.open(project, focus = false))
        assertNull(ForgeViewNavigator.selectRequest(project, 76))
    }

    fun `test the request's row is selected in the list and its context comes from the list`() {
        registerView()
        assertTrue(ForgeViewNavigator.open(project, focus = false))
        assertNotNull(ForgeViewNavigator.selectRequest(project, 76))
        assertEquals(2, list.selectedIndex)
        assertNotNull(ForgeViewNavigator.selectRequest(project, 41))
        assertEquals(0, list.selectedIndex)
        assertNull(ForgeViewNavigator.selectRequest(project, 99))
        assertTrue(ForgeViewNavigator.open(project, focus = true))
    }

    fun `test the timeline tab is selected when it is open, and nothing happens when it is not`() {
        assertFalse(ForgeViewNavigator.selectTimeline(project, 76))
        val manager = FileEditorManager.getInstance(project)
        val other = LightVirtualFile("Other.kt", "")
        val timeline = LightVirtualFile("#76", "timeline")
        manager.openFile(timeline, false)
        manager.openFile(other, false)
        assertTrue(ForgeViewNavigator.selectTimeline(project, 76))
        assertFalse(ForgeViewNavigator.selectTimeline(project, 77))
    }
}
