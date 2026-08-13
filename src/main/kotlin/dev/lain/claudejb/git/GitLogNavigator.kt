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
import com.intellij.vcsUtil.VcsUtil
import dev.lain.claudejb.diff.DiffPresenter

/**
 * Hands history off to **the IDE's own Git Log**, and builds nothing of its own.
 *
 * The plugin has no business re-implementing a commit graph: the platform's Version Control tool window already
 * has the log, the file history, the diff viewer, search and every action a user expects, in their theme and with
 * their shortcuts. A second, worse log inside a chat panel is the classic way an integration becomes a
 * maintenance liability — the same reasoning that keeps diffs on `DiffManager` (see `diff/DiffPresenter`) rather
 * than rendering them in the web view.
 *
 * Both entry points are **EDT-only** (they touch tool windows) and both return `false` instead of throwing when
 * there is nothing to show, so a caller can degrade without a `try`.
 */
object GitLogNavigator {

    /**
     * Brings the Version Control tool window — the one hosting the Git Log — to the front. False when the IDE has
     * no such tool window (no VCS mappings, or a product without it), which is a legitimate state, not an error.
     */
    fun showLog(project: Project): Boolean {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.VCS) ?: return false
        toolWindow.activate(null, true)
        return true
    }

    /**
     * Opens the IDE's own file-history view for [path] (absolute, either separator).
     *
     * **The containment check is the point of this method.** A path can reach here from model output, so before
     * anything is resolved on disk it goes through [DiffPresenter.isWithinRoot] against the project root — the
     * same canonicalizing, symlink-resolving gate the write path uses. Outside the project, the answer is no:
     * `~/.ssh/config` does not get a history view because a tool call named it.
     *
     * False when Git is unavailable, when the path is outside the project, when the file does not exist, or when
     * the VCS registered for it offers no history provider.
     */
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
