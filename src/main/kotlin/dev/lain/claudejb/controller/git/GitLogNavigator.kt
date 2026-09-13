package dev.lain.claudejb.controller.git

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.AbstractVcsHelper
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.vcs.log.impl.VcsLogNavigationUtil.jumpToHash
import com.intellij.vcs.log.impl.VcsProjectLog
import com.intellij.vcs.log.visible.filters.VcsLogFilterObject
import com.intellij.vcsUtil.VcsUtil
import dev.lain.claudejb.model.diff.DiffPresenter
import dev.lain.claudejb.util.logger

object GitLogNavigator {

    fun showLog(project: Project, focus: Boolean): Boolean {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.VCS) ?: return false
        toolWindow.activate(null, focus)
        return true
    }

    fun showRange(project: Project, exclusiveRef: String, inclusiveRef: String, focus: Boolean): Boolean {
        if (!project.service<GitHistoryService>().isAvailable() || !showLog(project, focus)) return false
        val filters = VcsLogFilterObject.collection(VcsLogFilterObject.fromRange(exclusiveRef, inclusiveRef))
        VcsProjectLog.runInMainLog(project) { it.filterUi.setFilters(filters) }
        return true
    }

    fun showCommit(project: Project, hash: String, focus: Boolean): Boolean {
        if (hash.isBlank() || !project.service<GitHistoryService>().isAvailable() || !showLog(project, focus)) return false
        VcsProjectLog.runInMainLog(project) { it.jumpToHash(hash, false, focus) }
        return true
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
