package dev.lain.claudejb.controller.commands.git

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import dev.lain.claudejb.controller.git.GitAvailability
import dev.lain.claudejb.util.edt
import dev.lain.claudejb.util.logger
import java.io.File

internal class GitInit(private val project: Project) {

    fun projectRootWithoutRepository(): File? =
        project.basePath?.let(::File)?.takeIf { it.isDirectory && !File(it, DOT_GIT).exists() }

    fun run(root: File, done: (Boolean) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val ok = initRepository(root)
            edt(project) {
                done(ok)
                if (ok) registerRepository(root)
            }
        }
    }

    private fun initRepository(root: File): Boolean {
        if (runGit(root, "init", "-b", GitPromptedActions.INITIAL_BRANCH)) return true
        return runGit(root, "init") && runGit(root, "symbolic-ref", "HEAD", "refs/heads/${GitPromptedActions.INITIAL_BRANCH}")
    }

    private fun runGit(root: File, vararg args: String): Boolean {
        val output = runCatching {
            val cmd = GeneralCommandLine(listOf(GIT) + args)
                .withWorkingDirectory(root.toPath())
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            CapturingProcessHandler(cmd).runProcess(GIT_TIMEOUT_MS, true)
        }.getOrElse {
            LOG.warn("Could not run `git ${args.joinToString(" ")}` in $root", it)
            return false
        }
        if (output.isTimeout || output.exitCode != 0) {
            LOG.warn("`git ${args.joinToString(" ")}` failed in $root (exit ${output.exitCode}): ${output.stderr.trim()}")
            return false
        }
        return true
    }

    private fun registerRepository(root: File) {
        val dir = LocalFileSystem.getInstance().findFileByPath(root.path) ?: return
        VfsUtil.markDirtyAndRefresh(true, true, true, dir)
        if (!GitAvailability.isGitPluginEnabled()) return
        val manager = ProjectLevelVcsManager.getInstance(project)
        val existing = manager.getDirectoryMappings()
        if (existing.any { it.vcs == GIT_VCS_NAME && it.directory == root.path }) return
        manager.setDirectoryMappings(existing + VcsDirectoryMapping(root.path, GIT_VCS_NAME))
    }

    private companion object {
        private val LOG = logger<GitInit>()

        private const val GIT_VCS_NAME = "Git"

        private const val GIT = "git"
        private const val DOT_GIT = ".git"

        private const val GIT_TIMEOUT_MS = 15_000
    }
}
