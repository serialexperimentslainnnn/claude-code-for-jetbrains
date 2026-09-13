package dev.lain.claudejb.headless

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.controller.mcp.IdePlaces
import dev.lain.claudejb.controller.mcp.ToolOutput
import dev.lain.claudejb.controller.mcp.ToolOutputListener

class IdePlacesHeadlessTest : BasePlatformTestCase() {

    private val places by lazy { IdePlaces(project) }

    private var performed = 0

    private val action = object : AnAction("Claude Test Action") {
        override fun actionPerformed(e: AnActionEvent) {
            performed++
        }
    }

    override fun setUp() {
        super.setUp()
        ActionManager.getInstance().registerAction(ACTION_ID, action)
    }

    override fun tearDown() {
        try {
            ActionManager.getInstance().unregisterAction(ACTION_ID)
        } finally {
            super.tearDown()
        }
    }

    fun `test an unknown verb, a missing required value and an unknown window are refused`() {
        assertFalse(places.open("teleport", emptyMap()))
        assertFalse(places.open("toolwindow", emptyMap()))
        assertFalse(places.open("toolwindow", mapOf("id" to "No Such Window")))
        assertFalse(places.open("commit", emptyMap()))
    }

    fun `test a registered tool window is activated, with or without a terminal tab`() {
        ToolWindowManager.getInstance(project).registerToolWindow("Claude Places") { anchor = ToolWindowAnchor.BOTTOM }
        assertTrue(places.open("toolwindow", mapOf("id" to "Claude Places")))
        assertFalse(places.open("terminal", emptyMap()))
        ToolWindowManager.getInstance(project).registerToolWindow("Terminal") { anchor = ToolWindowAnchor.BOTTOM }
        assertTrue(places.open("terminal", emptyMap()))
        assertTrue(places.open("terminal", mapOf("tab" to "Claude")))
    }

    fun `test a registered action is performed, and an unknown id is refused`() {
        assertTrue(places.open("action", mapOf("id" to ACTION_ID)))
        assertEquals(1, performed)
        assertFalse(places.open("action", mapOf("id" to "Claude.NoSuchAction")))
    }

    fun `test the places that need a window this fixture lacks say so instead of failing`() {
        assertFalse(places.open("run", mapOf("name" to "nothing")))
        assertFalse(places.open("log", emptyMap()))
        assertFalse(places.open("diff", mapOf("file" to "../outside.txt")))
        assertFalse(places.open("diff", mapOf("file" to "unchanged.txt")))
        assertFalse(places.open("problems", emptyMap()))
    }

    fun `test a tool's output line reaches the project bus`() {
        val heard = ArrayList<String>()
        project.messageBus.connect(testRootDisposable).subscribe(ToolOutput.TOPIC, ToolOutputListener { id, line -> heard += "$id:$line" })
        ToolOutput.line(project, "u1", "hello")
        assertEquals(listOf("u1:hello"), heard)
    }

    private companion object {
        const val ACTION_ID = "Claude.Test.Places"
    }
}
