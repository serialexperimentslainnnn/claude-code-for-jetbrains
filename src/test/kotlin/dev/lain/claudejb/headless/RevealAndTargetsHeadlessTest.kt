package dev.lain.claudejb.headless

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.content.ContentFactory
import dev.lain.claudejb.controller.mcp.IdeActions
import dev.lain.claudejb.controller.mcp.Reveal
import dev.lain.claudejb.controller.mcp.TargetContext
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JPanel

class RevealAndTargetsHeadlessTest : BasePlatformTestCase() {

    private lateinit var source: Path
    private val scope = CoroutineScope(SupervisorJob())
    private var toggled = false

    private val toggle = object : ToggleAction("Claude Test Toggle") {
        override fun isSelected(e: AnActionEvent): Boolean = toggled
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            toggled = state
        }
    }

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        source = Files.createDirectories(Path.of(project.basePath!!)).resolve("Target.txt")
        Files.writeString(source, "one two\nthree four\n")
        assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source))
        ActionManager.getInstance().registerAction(TOGGLE_ID, toggle)
    }

    override fun tearDown() {
        try {
            ActionManager.getInstance().unregisterAction(TOGGLE_ID)
            scope.cancel()
            Files.deleteIfExists(source)
        } finally {
            super.tearDown()
        }
    }

    private fun args(json: String) = ToolArgs(Json.parseToJsonElement(json).jsonObject)

    fun `test a target names one thing, starts at line and column one, and may carry a selection`() {
        val plain = TargetContext.target(args("""{"path":"Target.txt"}"""))
        assertTrue(plain.named)
        assertEquals(1, plain.line)
        assertNull(plain.selection)
        val selected = TargetContext.target(args("""{"path":"Target.txt","line":"2","column":"1","to_line":"2"}"""))
        assertEquals(2, selected.selection!!.toLine)
        assertEquals(Int.MAX_VALUE, selected.selection.toColumn)
        assertFalse(TargetContext.target(args("{}")).named)
        assertThrows(ToolException::class.java) { TargetContext.target(args("""{"path":"a","hash":"b"}""")) }
        assertThrows(ToolException::class.java) { TargetContext.target(args("""{"path":"a","line":"0"}""")) }
    }

    fun `test a file target opens an editor and carries the file, its psi and the caret's element`() = runBlocking {
        val targets = TargetContext(project)
        val context = targets.of(TargetContext.target(args("""{"path":"Target.txt","line":"2","column":"1","to_line":"2","to_column":"6"}""")))
        readAction {
            assertEquals("Target.txt", context.getData(CommonDataKeys.VIRTUAL_FILE)!!.name)
            assertNotNull(context.getData(CommonDataKeys.PSI_FILE))
            assertNotNull(context.getData(CommonDataKeys.PSI_ELEMENT))
        }
        val directory = targets.of(TargetContext.target(args("""{"path":"."}""")))
        readAction { assertTrue(directory.getData(CommonDataKeys.VIRTUAL_FILE)!!.isDirectory) }
        val bare = targets.of(TargetContext.target(args("{}")))
        readAction { assertSame(project, bare.getData(CommonDataKeys.PROJECT)) }
    }

    fun `test reveal opens a file without focus, shows a registered window and its content, and says no for what is missing`() = runBlocking {
        val reveal = Reveal(project)
        val file = LocalFileSystem.getInstance().findFileByNioFile(source)!!
        assertTrue(reveal.file(file, line = 2, column = 3, preview = true))
        assertTrue(FileEditorManager.getInstance(project).isFileOpen(file))
        assertFalse(reveal.toolWindow("No Such Window"))
        val window = ToolWindowManager.getInstance(project).registerToolWindow("Claude Reveal") { anchor = ToolWindowAnchor.RIGHT }
        window.contentManager.addContent(ContentFactory.getInstance().createContent(JPanel(), "First", false))
        window.contentManager.addContent(ContentFactory.getInstance().createContent(JPanel(), "Second", false))
        assertTrue(reveal.toolWindow("Claude Reveal"))
        assertTrue(reveal.content("Claude Reveal", "Second"))
        assertFalse(reveal.content("Claude Reveal", "Third"))
        assertFalse(reveal.content("No Such Window", "First"))
    }

    fun `test an action is dispatched in a target's context, a toggle flips and reads back, and the unknown is named`() = runBlocking {
        val actions = IdeActions(project, scope)
        assertTrue(actions.toggle(TOGGLE_ID, true))
        assertTrue(toggled)
        assertFalse(actions.toggle(TOGGLE_ID, null))
        assertFalse(toggled)
        assertFalse(actions.toggle(TOGGLE_ID, false))
        actions.dispatch(TOGGLE_ID, TargetContext.target(args("""{"path":"Target.txt"}""")))
        val missing = runCatching { actions.dispatch("Claude.NoSuchAction") }.exceptionOrNull()
        assertTrue(missing is ToolException && missing.message!!.contains("no action"))
    }

    private companion object {
        const val TOGGLE_ID = "Claude.Test.Toggle"
    }
}
