package dev.lain.claudejb.ui

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionResult
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import dev.lain.claudejb.context.EditorContextProvider
import dev.lain.claudejb.forge.ForgeAnswer
import dev.lain.claudejb.forge.ForgeProbe
import dev.lain.claudejb.forge.ForgeProvider
import dev.lain.claudejb.forge.ForgeRepo
import dev.lain.claudejb.forge.ForgeService
import dev.lain.claudejb.forge.ForgeTokens
import dev.lain.claudejb.git.ForgeViewNavigator
import dev.lain.claudejb.git.GitAvailability
import dev.lain.claudejb.git.GitCommitInfo
import dev.lain.claudejb.git.GitHistoryService
import dev.lain.claudejb.git.GitLogNavigator
import dev.lain.claudejb.git.GitLogScope
import dev.lain.claudejb.git.GitRemoteProvider
import dev.lain.claudejb.session.AttentionReason
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.SessionListener
import dev.lain.claudejb.ui.jcef.JcefGitData
import java.awt.datatransfer.StringSelection
import java.io.File

@Service(Service.Level.PROJECT)
internal class GitIntegration(private val project: Project) {

    private var snapshot: JcefGitData.Snapshot? = null

    private val states = mutableMapOf<String, JcefGitData.ActionState>()

    private var collecting = false
    private var again = false

    fun snapshot(): JcefGitData.Snapshot? = snapshot

    fun refresh(onChanged: () -> Unit) {
        if (collecting) {
            again = true
            return
        }
        collecting = true
        val openFile = EditorContextProvider.currentFilePath(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            val collected = runCatching { collect(openFile) }.getOrElse {
                LOG.warn("Git snapshot collection failed for ${project.name}", it)
                null
            }
            edt {
                collecting = false
                if (collected != null) snapshot = collected
                onChanged()
                if (again) {
                    again = false
                    refresh(onChanged)
                }
            }
        }
    }

    fun perform(id: String, hash: String, chat: () -> ClaudeSession, onChanged: () -> Unit) {
        val action = GitActionCatalog.byId(id) ?: run {
            LOG.warn("Git view asked for an unknown action id: $id")
            return
        }
        if (action.takesCommit && !GitActionCatalog.isCommitHash(hash)) {
            LOG.warn("Git view asked for '$id' with a value that is not a commit hash; refusing")
            settle(action.id, JcefGitData.ActionState.FAILED, onChanged)
            return
        }
        when (action.kind) {
            GitActionCatalog.Kind.DIRECT -> runDirect(action, onChanged)
            GitActionCatalog.Kind.PROMPT -> runPrompt(action, hash, chat, onChanged)
            GitActionCatalog.Kind.IDE -> invokeIde(action, onChanged)
            GitActionCatalog.Kind.HOST -> runHost(action, hash, onChanged)
        }
    }

    private fun collect(openFilePath: String?): JcefGitData.Snapshot {
        if (!GitAvailability.isGitPluginEnabled()) return JcefGitData.Snapshot(available = false)
        val history = project.service<GitHistoryService>()
        val root = history.primaryRepositoryRoot()
        if (root == null) {
            return JcefGitData.Snapshot(available = true, actionStates = states.toMap())
        }
        val changes = history.workingTreeChanges()
        val branch = history.currentBranch()
        val forge = forgeRepo(history)
        val runs = forge.drawable(branch) { repo, on -> ForgeService.runs(repo, on) }
        return JcefGitData.Snapshot(
            available = true,
            repo = JcefGitData.Repo(
                present = true,
                branch = branch,
                head = history.headRevision(),
                root = root,
            ),
            changes = changes,
            commits = history.recentCommits(limit = GRAPH_COMMIT_LIMIT, scope = GitLogScope.EVERY_LINE_OF_DEVELOPMENT),
            refs = history.refs(),
            changedFileOpen = relativeChangedFile(root, changes, openFilePath) != null,
            conflicted = history.hasConflicts(),
            actionStates = states.toMap(),
            topology = history.branchTopology(),
            pullRequests = forge.drawable(branch) { repo, on -> ForgeService.openPullRequests(repo, on) },
            runs = runs,
            lastRun = runs?.firstOrNull(),
            forgeConfigured = forge != null,
        )
    }

    private fun forgeRepo(history: GitHistoryService): ForgeRepo? {
        val remote = history.primaryRemote() ?: return null
        val host = remote.host ?: return null
        val owner = remote.owner ?: return null
        val name = remote.repo ?: return null
        val token = ForgeTokens.get(host) ?: return null
        val provider = when (remote.provider) {
            GitRemoteProvider.GITHUB -> ForgeProvider.GITHUB
            GitRemoteProvider.GITLAB -> ForgeProvider.GITLAB
            GitRemoteProvider.OTHER -> ForgeProbe.detect(host, token) ?: return null
        }
        return ForgeRepo(provider, host, owner, name)
    }

    private fun <T> ForgeRepo?.drawable(branch: String?, ask: (ForgeRepo, String) -> ForgeAnswer<T>): T? {
        val repo = this ?: return null
        val on = branch?.takeIf { it.isNotBlank() } ?: return null
        return when (val answer = ask(repo, on)) {
            is ForgeAnswer.Known -> answer.value
            is ForgeAnswer.Silent -> null
        }
    }

    private fun relativeChangedFile(root: String, changes: List<String>, absolutePath: String?): String? {
        val absolute = absolutePath ?: return null
        return GitCommitInfo.relativize(root, absolute).takeIf { it in changes }
    }

    private fun runDirect(action: GitActionCatalog.GitAction, onChanged: () -> Unit) {
        if (action.id != INIT) {
            LOG.warn("No direct command is wired for Git action '${action.id}'")
            return
        }
        val root = project.basePath?.let(::File)
        if (root == null || !root.isDirectory || File(root, DOT_GIT).exists()) {
            settle(action.id, JcefGitData.ActionState.FAILED, onChanged)
            return
        }
        states[action.id] = JcefGitData.ActionState.RUNNING
        onChanged()
        ApplicationManager.getApplication().executeOnPooledThread {
            val ok = gitInit(root)
            edt {
                settle(action.id, if (ok) JcefGitData.ActionState.COMPLETED else JcefGitData.ActionState.FAILED, onChanged)
                if (ok) registerRepository(root)
            }
        }
    }

    private fun gitInit(root: File): Boolean {
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

    private fun runPrompt(
        action: GitActionCatalog.GitAction,
        hash: String,
        chat: () -> ClaudeSession,
        onChanged: () -> Unit,
    ) {
        val text = promptFor(action, hash)
        if (text == null) {
            settle(action.id, JcefGitData.ActionState.FAILED, onChanged)
            return
        }
        val session = chat()
        states[action.id] = JcefGitData.ActionState.RUNNING
        onChanged()
        session.addListener(TurnWatch(action.id, session, onChanged))
        session.send(text)
    }

    private fun promptFor(action: GitActionCatalog.GitAction, hash: String): String? {
        val history = project.service<GitHistoryService>()
        val root = history.primaryRepositoryRoot() ?: return null
        return when (action.id) {
            COMMIT -> history.workingTreeChanges().takeIf { it.isNotEmpty() }?.let(GitPromptedActions::commitPrompt)

            REVERT_FILE -> relativeChangedFile(root, history.workingTreeChanges(), EditorContextProvider.currentFilePath(project))
                ?.let(GitPromptedActions::revertFilePrompt)

            COMMIT_REVERT_TO_BRANCH -> GitPromptedActions.revertToCommitOnNewBranchPrompt(hash)

            COMMIT_REVERT -> GitPromptedActions.revertCommitPrompt(hash)

            else -> {
                LOG.warn("No prompt is wired for Git action '${action.id}'")
                null
            }
        }
    }

    private fun runHost(action: GitActionCatalog.GitAction, hash: String, onChanged: () -> Unit) {
        val done = when (action.id) {
            COMMIT_COPY_HASH -> {
                CopyPasteManager.getInstance().setContents(StringSelection(hash))
                true
            }

            COMMIT_DIFF -> GitLogNavigator.showCommit(project, hash)

            FORGE_VIEW -> ForgeViewNavigator.open(project)

            GIT_LOG -> GitLogNavigator.showLog(project)

            else -> {
                LOG.warn("No host action is wired for Git action '${action.id}'")
                false
            }
        }
        settle(action.id, if (done) JcefGitData.ActionState.COMPLETED else JcefGitData.ActionState.FAILED, onChanged)
    }

    private inner class TurnWatch(
        private val id: String,
        private val session: ClaudeSession,
        private val onChanged: () -> Unit,
    ) : SessionListener {

        private var started = false

        override fun onStateChanged() {
            if (session.turnActive) started = true
        }

        override fun onAttention(reason: AttentionReason) {
            if (!started) return
            if (reason != AttentionReason.TURN_DONE && reason != AttentionReason.ERROR) return
            session.removeListener(this)
            val state = if (reason == AttentionReason.ERROR) {
                JcefGitData.ActionState.FAILED
            } else {
                JcefGitData.ActionState.COMPLETED
            }
            settle(id, state, onChanged)
        }
    }

    private fun invokeIde(action: GitActionCatalog.GitAction, onChanged: () -> Unit) {
        val actionId = action.ideActionId ?: run {
            LOG.warn("Git action '${action.id}' says it is an IDE action but names none")
            settle(action.id, JcefGitData.ActionState.FAILED, onChanged)
            return
        }
        val target = ActionManager.getInstance().getAction(actionId) ?: run {
            LOG.warn("This IDE has no action '$actionId'; the Git view's '${action.id}' button does nothing")
            settle(action.id, JcefGitData.ActionState.FAILED, onChanged)
            return
        }
        val component = ClaudeToolWindowFactory.contextComponent(project)
        val context = if (component != null) {
            DataManager.getInstance().getDataContext(component)
        } else {
            SimpleDataContext.getProjectContext(project)
        }
        val event = AnActionEvent.createEvent(
            target,
            context,
            null,
            ActionPlaces.TOOLWINDOW_CONTENT,
            ActionUiKind.TOOLBAR,
            null,
        )
        ActionUtil.updateAction(target, event)
        if (!event.presentation.isEnabled || !event.presentation.isVisible) {
            LOG.warn("The IDE refused '$actionId' in this context (enabled=${event.presentation.isEnabled})")
            settle(action.id, JcefGitData.ActionState.FAILED, onChanged)
            return
        }
        if (!IdeActionPrompt.confirmed(project, action)) return
        val result = ActionUtil.performAction(target, event)
        settle(action.id, stateOf(result), onChanged)
    }

    private fun stateOf(result: AnActionResult): JcefGitData.ActionState = when {
        result.isPerformed -> JcefGitData.ActionState.COMPLETED
        else -> JcefGitData.ActionState.FAILED
    }

    private fun settle(id: String, state: JcefGitData.ActionState, onChanged: () -> Unit) {
        states[id] = state
        onChanged()
    }

    private fun edt(block: () -> Unit) = ApplicationManager.getApplication().invokeLater({
        if (!project.isDisposed) block()
    }, ModalityState.any())

    companion object {

        fun getInstance(project: Project): GitIntegration = project.service()

        const val GRAPH_COMMIT_LIMIT = 100

        const val INIT = "init"
        const val COMMIT = "commit"
        const val REVERT_FILE = "revertFile"

        const val COMMIT_DIFF = "commitDiff"
        const val COMMIT_COPY_HASH = "commitCopyHash"
        const val FORGE_VIEW = "forgeView"
        const val GIT_LOG = "gitLog"
        const val COMMIT_REVERT_TO_BRANCH = "commitRevertToBranch"
        const val COMMIT_REVERT = "commitRevert"

        private const val GIT_VCS_NAME = "Git"

        private const val GIT = "git"
        private const val DOT_GIT = ".git"

        private const val GIT_TIMEOUT_MS = 15_000

        private val LOG = logger<GitIntegration>()
    }
}
