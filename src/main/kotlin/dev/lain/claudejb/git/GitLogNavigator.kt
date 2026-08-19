package dev.lain.claudejb.git

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.AbstractVcsHelper
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.vcs.log.impl.HashImpl
import com.intellij.vcs.log.impl.VcsLogNavigationUtil
import com.intellij.vcsUtil.VcsUtil
import dev.lain.claudejb.diff.DiffPresenter

object GitLogNavigator {

    fun showLog(project: Project): Boolean {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.VCS) ?: return false
        toolWindow.activate(null, true)
        return true
    }

    fun showCommit(project: Project, hash: String): Boolean {
        if (hash.isBlank()) return false
        val history = project.service<GitHistoryService>()
        if (!history.isAvailable()) return false
        val root = history.primaryRepositoryRoot() ?: return false
        val rootFile = LocalFileSystem.getInstance().findFileByPath(FileUtil.toSystemIndependentName(root))
            ?: return false
        return runCatching {
            VcsLogNavigationUtil.jumpToRevisionAsync(project, rootFile, HashImpl.build(hash))
            true
        }.getOrElse {
            LOG.warn("Could not show commit $hash in the Git Log", it)
            false
        }
    }

    fun showFileHistory(project: Project, path: String): Boolean {
        if (!project.service<GitHistoryService>().isAvailable()) return false
        if (!DiffPresenter.isWithinRoot(path, project.basePath)) {
            LOG.warn("Refusing to show file history outside the project root")
            return false
        }
        val file = LocalFileSystem.getInstance().findFileByPath(FileUtil.toSystemIndependentName(path)) ?: return false
        val vcs = ProjectLevelVcsManager.getInstance(project).getVcsFor(file) ?: return false
        val provider = vcs.vcsHistoryProvider ?: return false
        AbstractVcsHelper.getInstance(project).showFileHistory(provider, VcsUtil.getFilePath(file), vcs)
        return true
    }

    private val LOG = logger<GitLogNavigator>()
}
