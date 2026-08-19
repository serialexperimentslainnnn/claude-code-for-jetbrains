package dev.lain.claudejb.ui

import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.ide.highlighter.ArchiveFileType
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import dev.lain.claudejb.diff.DiffPresenter
import java.io.File
import java.net.URLDecoder

internal class LinkNavigator(private val project: Project) {

    fun open(url: String) {
        val u = url.trim()
        when {
            u.lowercase().startsWith("https://") -> BrowserUtil.browse(u)
            u.startsWith("jb://open") -> openJbLink(u)
            LinkResolver.isFilePathHref(u) -> openPath(u.substringBefore('#').trim())
        }
    }

    private fun openJbLink(url: String) {
        val params = url.substringAfter('?', "").split('&').mapNotNull {
            val k = it.substringBefore('=', "")
            val v = it.substringAfter('=', "")
            if (k.isEmpty()) null else k to runCatching { URLDecoder.decode(v, Charsets.UTF_8) }.getOrDefault(v)
        }.toMap()
        val raw = params["file"] ?: return
        openPath(raw, params["line"]?.toIntOrNull() ?: 1)
    }

    fun openPath(raw: String, line: Int = 1) {
        val path = resolveAgainstRoot(raw) ?: return
        if (!LinkResolver.isOpenable(path, project.basePath)) return
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: return
        if (vf.isDirectory || vf.fileType is ArchiveFileType) {
            revealDirectory(vf)
            return
        }
        OpenFileDescriptor(project, vf, line.coerceAtLeast(1) - 1, 0).navigate(true)
        selectInProjectView(vf)
    }

    private fun resolveAgainstRoot(raw: String): String? {
        val f = File(LinkResolver.expandHome(raw))
        if (f.isAbsolute) return f.path
        val root = project.basePath ?: return null
        return File(root, f.path).path
    }

    private fun selectInProjectView(file: VirtualFile) {
        if (!DiffPresenter.isWithinRoot(file.path, project.basePath)) return
        val tw = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.PROJECT_VIEW) ?: return
        if (!tw.isVisible) return
        runCatching { ProjectView.getInstance(project).select(null, file, false) }
    }

    private fun revealDirectory(target: VirtualFile) {
        if (DiffPresenter.isWithinRoot(target.path, project.basePath)) {
            val select = { ProjectView.getInstance(project).select(null, target, true) }
            val tw = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.PROJECT_VIEW)
            if (tw != null) tw.activate(select, true) else select()
        } else {
            RevealFileAction.openDirectory(File(target.path))
        }
    }
}
