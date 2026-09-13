package dev.lain.claudejb.controller.commands.git

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.lain.claudejb.controller.commands.git.GitActionCatalog.Behaviour
import dev.lain.claudejb.controller.context.EditorContextProvider
import dev.lain.claudejb.controller.git.GitAvailability
import dev.lain.claudejb.controller.git.GitHistoryService
import dev.lain.claudejb.controller.session.AttentionLanding
import dev.lain.claudejb.controller.session.AttentionReason
import dev.lain.claudejb.controller.session.ClaudeSession
import dev.lain.claudejb.controller.session.SessionListener
import dev.lain.claudejb.model.git.GitCommitInfo
import dev.lain.claudejb.model.git.GitLogScope
import dev.lain.claudejb.util.edt
import dev.lain.claudejb.util.logger
import dev.lain.claudejb.view.git.JcefGitData
import dev.lain.claudejb.view.git.JcefGitData.ActionState

@Service(Service.Level.PROJECT)
internal class GitIntegration(private val project: Project) {

    private var snapshot: JcefGitData.Snapshot? = null

    private val states = mutableMapOf<String, ActionState>()

    private var inFlight: List<() -> Unit>? = null
    private val queued = ArrayList<() -> Unit>()

    fun snapshot(): JcefGitData.Snapshot? = snapshot

    fun refresh(onChanged: () -> Unit) {
        if (inFlight != null) {
            queued += onChanged
            return
        }
        collectFor(listOf(onChanged))
    }

    private fun collectFor(waiting: List<() -> Unit>) {
        inFlight = waiting
        val openFile = EditorContextProvider.currentFilePath(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            val collected = runCatching { collect(openFile) }.getOrElse {
                LOG.warn("Git snapshot collection failed for ${project.name}", it)
                null
            }
            edt(project) {
                inFlight = null
                if (collected != null) snapshot = collected
                waiting.forEach { it() }
                if (queued.isNotEmpty()) collectFor(queued.toList().also { queued.clear() })
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
            settle(action.id, ActionState.FAILED, onChanged)
            return
        }
        when (val behaviour = action.behaviour) {
            Behaviour.InitRepository -> initRepository(action.id, onChanged)
            is Behaviour.Prompt -> runPrompt(action.id, behaviour, hash, chat, onChanged)
            is Behaviour.Ide -> settle(action.id, IdeActionInvoker.invoke(project, behaviour.actionId, action.id), onChanged)
            is Behaviour.Host -> settle(action.id, stateOf(behaviour.run(project, hash)), onChanged)
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
        )
    }

    private fun relativeChangedFile(root: String, changes: List<String>, absolutePath: String?): String? {
        val absolute = absolutePath ?: return null
        return GitCommitInfo.relativize(root, absolute).takeIf { it in changes }
    }

    private fun initRepository(id: String, onChanged: () -> Unit) {
        val init = GitInit(project)
        val root = init.projectRootWithoutRepository()
        if (root == null) {
            settle(id, ActionState.FAILED, onChanged)
            return
        }
        states[id] = ActionState.RUNNING
        onChanged()
        init.run(root) { ok -> settle(id, stateOf(ok), onChanged) }
    }

    private fun runPrompt(id: String, prompt: Behaviour.Prompt, hash: String, chat: () -> ClaudeSession, onChanged: () -> Unit) {
        val text = subject()?.let { prompt.text(it, hash) }
        if (text == null) {
            settle(id, ActionState.FAILED, onChanged)
            return
        }
        val session = chat()
        states[id] = ActionState.RUNNING
        onChanged()
        session.addListener(TurnWatch(id, session, onChanged))
        session.send(text)
    }

    private fun subject(): GitActionCatalog.PromptSubject? {
        val history = project.service<GitHistoryService>()
        val root = history.primaryRepositoryRoot() ?: return null
        return object : GitActionCatalog.PromptSubject {
            override val changes: List<String> by lazy { history.workingTreeChanges() }
            override val changedFile: String? by lazy {
                relativeChangedFile(root, changes, EditorContextProvider.currentFilePath(project))
            }
        }
    }

    private inner class TurnWatch(
        private val id: String,
        private val session: ClaudeSession,
        private val onChanged: () -> Unit,
    ) : SessionListener {

        private var started = false

        override fun onStateChanged() {
            if (session.turn.active) started = true
        }

        override fun onAttention(reason: AttentionReason, landing: AttentionLanding) {
            if (!started) return
            if (reason != AttentionReason.TURN_DONE && reason != AttentionReason.ERROR) return
            session.removeListener(this)
            settle(id, stateOf(reason != AttentionReason.ERROR), onChanged)
        }
    }

    private fun stateOf(done: Boolean): ActionState = if (done) ActionState.COMPLETED else ActionState.FAILED

    private fun settle(id: String, state: ActionState, onChanged: () -> Unit) {
        states[id] = state
        onChanged()
    }

    companion object {

        fun getInstance(project: Project): GitIntegration = project.service()

        const val GRAPH_COMMIT_LIMIT = 100

        private val LOG = logger<GitIntegration>()
    }
}
