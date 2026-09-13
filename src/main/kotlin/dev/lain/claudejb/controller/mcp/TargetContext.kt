package dev.lain.claudejb.controller.mcp

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.EdtExecutorService
import com.intellij.vcs.log.impl.VcsLogNavigationUtil.jumpToHash
import com.intellij.vcs.log.impl.VcsProjectLog
import dev.lain.claudejb.controller.git.GitHistoryService
import dev.lain.claudejb.controller.git.GitLogNavigator
import dev.lain.claudejb.controller.mcp.tools.code.Locations
import dev.lain.claudejb.controller.mcp.tools.ops.ServiceActions
import dev.lain.claudejb.controller.mcp.tools.ops.ServiceTree
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path

internal class TargetContext(private val project: Project) {

    class Target(
        val path: String?,
        val line: Int,
        val column: Int,
        val hash: String?,
        val node: String?,
        val preview: Boolean = true,
        val selection: Selection? = null,
    ) {
        val named: Boolean get() = path != null || hash != null || node != null
    }

    class Selection(val toLine: Int, val toColumn: Int)

    suspend fun of(target: Target): DataContext = when {
        target.path != null -> file(target)
        target.hash != null -> commit(target.hash)
        target.node != null -> node(target.node)
        else -> withContext(Dispatchers.EDT) { project() }
    }

    fun project(): DataContext = SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project)
        .add(CommonDataKeys.VIRTUAL_FILE, repositoryRoot())
        .build()

    private suspend fun file(target: Target): DataContext {
        val path = target.path.orEmpty()
        val located = readAction { Locations.any(project, path) }
        if (located.isDirectory) return directory(located)
        val psiFile = readAction { Locations.psiFile(project, path) }
        val file = located
        return FocusKeeper.keep(project) {
            val descriptor = OpenFileDescriptor(project, file, target.line - 1, target.column - 1).setUsePreviewTab(target.preview)
            val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, false)
            target.selection?.let { editor?.let { e -> select(e, target, it) } }
            val element = editor?.let { psiFile.findElementAt(it.caretModel.offset) }
            val base = if (editor != null) DataManager.getInstance().getDataContext(editor.contentComponent) else project()
            SimpleDataContext.builder()
                .setParent(base)
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE, file)
                .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(file))
                .add(CommonDataKeys.PSI_FILE, psiFile)
                .add(CommonDataKeys.PSI_ELEMENT, element ?: psiFile)
                .build()
        }
    }

    private suspend fun directory(dir: VirtualFile): DataContext {
        val psi = readAction { PsiManager.getInstance(project).findDirectory(dir) }
        return SimpleDataContext.builder()
            .setParent(project())
            .add(CommonDataKeys.VIRTUAL_FILE, dir)
            .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(dir))
            .add(CommonDataKeys.PSI_ELEMENT, psi)
            .build()
    }

    private fun select(editor: Editor, target: Target, selection: Selection) {
        val start = editor.logicalPositionToOffset(LogicalPosition(target.line - 1, target.column - 1))
        val end = editor.logicalPositionToOffset(LogicalPosition(selection.toLine - 1, selection.toColumn - 1))
        editor.selectionModel.setSelection(minOf(start, end), maxOf(start, end))
    }

    private suspend fun commit(hash: String): DataContext {
        val context = CompletableDeferred<DataContext>()
        withContext(Dispatchers.EDT) {
            FocusKeeper.keeping(project) { GitLogNavigator.showLog(project, focus = false) }
            VcsProjectLog.runInMainLog(project) { ui ->
                val jump = ui.jumpToHash(hash, false, false)
                jump.addListener({ context.complete(DataManager.getInstance().getDataContext(ui.table)) }, EdtExecutorService.getInstance())
            }
        }
        return withTimeoutOrNull(LOG_TIMEOUT_MILLIS) { context.await() }
            ?: throw ToolException("the Git Log did not select $hash in time; is the log still loading?")
    }

    private suspend fun node(path: String): DataContext {
        val node = withContext(Dispatchers.Default) { ServiceTree(project).find(path) }
        return withContext(Dispatchers.EDT) { ServiceActions(project, node).context() }
    }

    private fun repositoryRoot(): VirtualFile? =
        project.service<GitHistoryService>().primaryRepositoryRoot()?.let { LocalFileSystem.getInstance().findFileByNioFile(Path.of(it)) }

    companion object {

        private const val LOG_TIMEOUT_MILLIS = 15_000L

        val PARAMS: List<Param> = listOf(
            Param("path", "A file to act on: the action runs with that file, its PSI and an editor on it as context", required = false),
            Param("line", "1-based caret line inside path (default 1)", type = "integer", required = false),
            Param("column", "1-based caret column inside path (default 1)", type = "integer", required = false),
            Param("hash", "A commit to act on: it is selected in the Git Log and the action runs with the log's context", required = false),
            Param("node", "A Services node path, as services lists it, to act on", required = false),
        )

        val SELECTION_PARAMS: List<Param> = listOf(
            Param("to_line", "1-based line where the selection ends (default: no selection)", type = "integer", required = false),
            Param("to_column", "1-based column where the selection ends (default: end of to_line)", type = "integer", required = false),
        )

        fun target(args: ToolArgs, preview: Boolean = true): Target {
            val target = Target(
                args.optionalString("path"),
                args.int("line", 1),
                args.int("column", 1),
                args.optionalString("hash"),
                args.optionalString("node"),
                preview,
                args.optionalString("to_line")?.let { Selection(args.int("to_line", 1), args.int("to_column", Int.MAX_VALUE)) },
            )
            if (listOfNotNull(target.path, target.hash, target.node).size > 1) throw ToolException("give one target: path, hash or node")
            if (target.line < 1 || target.column < 1) throw ToolException("line and column start at 1")
            return target
        }
    }
}
