package dev.lain.claudejb.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.history.VcsHistoryProvider
import com.intellij.openapi.vfs.VirtualFile
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

/**
 * Pins **every Git4Idea / VCS API this package calls** against the platform we build on, and pins that none of
 * them is deprecated.
 *
 * **Why this exists at all.** 4.4.1 shipped a dead `/login`: `TerminalLauncher` reflected on platform classes
 * that had been removed, every lookup returned `false` instead of throwing, and the feature simply never worked
 * — nothing in `idea.log`, no test red, a user hitting a dead end. `TerminalApiContractTest` was written so a
 * removal fails the BUILD instead of the user. Git4Idea is the same kind of dependency: bundled, versioned with
 * the IDE, and free to move under us. So the same treatment.
 *
 * **The deprecation half is a real gate, not decoration.** This repository's standing rule is "never ship a
 * deprecated or scheduled-for-removal API"; `verifyPlugin` enforces it across the declared IDE range with
 * `DEPRECATED_API_USAGES` in its failure levels. That check runs against DOWNLOADED IDEs and takes minutes. This
 * one runs against the build classpath in milliseconds and fails the moment JetBrains deprecates any member the
 * package calls — which is when the migration is cheap, not when the release is being cut. (How many that is, is
 * whatever the assertions below add up to. A count written into this sentence goes stale on the next one.)
 *
 * Reflection rather than a direct call because these are *existence* assertions: a compile-time call proves the
 * symbol exists on the compile classpath and nothing about the shape we depend on (parameter list, return type),
 * which is exactly what changed under `TerminalLauncher`.
 */
class GitApiContractTest {

    // ── git4idea: the repository model ────────────────────────────────────────────────────────────────────────

    @Test
    fun `GitRepositoryManager still hands out the project's repositories`() {
        val manager = load("git4idea.repo.GitRepositoryManager")
        val getInstance = manager.getMethod("getInstance", Project::class.java).assertNotDeprecated()
        assertTrue(manager.isAssignableFrom(getInstance.returnType), "getInstance must return the manager itself")
        val repositories = manager.getMethod("getRepositories").assertNotDeprecated()
        assertTrue(List::class.java.isAssignableFrom(repositories.returnType), "getRepositories must return a List")
    }

    @Test
    fun `a GitRepository is a dvcs Repository, which is where root, branch and HEAD come from`() {
        val repository = load("git4idea.repo.GitRepository")
        val dvcsRepository = load("com.intellij.dvcs.repo.Repository")
        assertTrue(
            dvcsRepository.isAssignableFrom(repository),
            "GitRepository must still extend com.intellij.dvcs.repo.Repository — getRoot/getCurrentBranchName/" +
                "getCurrentRevision are declared there, not on the Git-specific interface.",
        )
        assertTrue(VirtualFile::class.java.isAssignableFrom(repository.getMethod("getRoot").assertNotDeprecated().returnType))
        assertTrue(String::class.java.isAssignableFrom(repository.getMethod("getCurrentBranchName").assertNotDeprecated().returnType))
        assertTrue(String::class.java.isAssignableFrom(repository.getMethod("getCurrentRevision").assertNotDeprecated().returnType))
    }

    @Test
    fun `a GitRepository still answers the tracking and remote questions the topology is built from`() {
        val repository = load("git4idea.repo.GitRepository")
        val trackInfo = repository.getMethod("getBranchTrackInfo", String::class.java).assertNotDeprecated()
        assertTrue(load("git4idea.repo.GitBranchTrackInfo").isAssignableFrom(trackInfo.returnType))
        assertTrue(Collection::class.java.isAssignableFrom(repository.getMethod("getRemotes").assertNotDeprecated().returnType))
    }

    @Test
    fun `the upstream branch is still named for local operations, which is the ref git rev-list is given`() {
        // `origin/main`, not `main`: the remote-operations name is what you push, the local one is what you can
        // put either side of `..`. Handing `git rev-list` the wrong one asks about a branch that does not exist
        // locally, and the platform answers null — an ahead/behind that is silently always unknown.
        val remoteBranch = load("git4idea.GitRemoteBranch")
        assertTrue(String::class.java.isAssignableFrom(remoteBranch.getMethod("getNameForLocalOperations").assertNotDeprecated().returnType))
        val track = load("git4idea.repo.GitBranchTrackInfo")
        assertTrue(remoteBranch.isAssignableFrom(track.getMethod("getRemoteBranch").assertNotDeprecated().returnType))
    }

    @Test
    fun `a GitRemote is still a name plus its urls, and still knows what origin is called`() {
        val remote = load("git4idea.repo.GitRemote")
        assertTrue(String::class.java.isAssignableFrom(remote.getMethod("getName").assertNotDeprecated().returnType))
        assertTrue(String::class.java.isAssignableFrom(remote.getMethod("getFirstUrl").assertNotDeprecated().returnType))
        // The field's EXISTENCE, not its value: GitGateway reads the constant precisely so that whatever Git calls
        // the conventional remote is what the plugin looks for. Reading it here would also initialize the class,
        // which [load] deliberately does not do.
        val origin = remote.getField("ORIGIN")
        assertTrue(String::class.java == origin.type, "GitRemote.ORIGIN must still be the remote's conventional name")
    }

    // ── git4idea: the log ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `GitHistoryUtils history still takes a root plus raw git-log parameters`() {
        // The vararg IS the contract: it is how `-n <limit>` reaches `git log`. A signature change here would
        // silently become "history() with no limit" — the whole repository, on every call.
        //
        // It carries the REF SELECTION too (`HEAD --branches --remotes --tags`, plus `--topo-order`), which is
        // the difference between a branch graph and a straight line: with no revision named, `git log` walks
        // only what HEAD reaches, so a commit living on another branch is never printed and there is nothing to
        // fork into. Those are Git's own command-line arguments and no reflection can check them — what is
        // checkable, and what this pins, is that the vararg through which they travel still exists.
        val method = load("git4idea.history.GitHistoryUtils")
            .getMethod("history", Project::class.java, VirtualFile::class.java, Array<String>::class.java)
            .assertNotDeprecated()
        assertTrue(List::class.java.isAssignableFrom(method.returnType), "history() must return a List of commits")
    }

    @Test
    fun `GitHistoryUtils still counts commits between two refs, and still returns that count as text`() {
        // The String return is the contract, not an accident: the platform catches the VcsException and returns
        // null, so "the command failed" and "the answer is 0" arrive as two different values only because one of
        // them is not a number. A signature change to int would collapse them.
        val method = load("git4idea.history.GitHistoryUtils")
            .getMethod("getNumberOfCommitsBetween", load("git4idea.repo.GitRepository"), String::class.java, String::class.java)
            .assertNotDeprecated()
        assertTrue(String::class.java.isAssignableFrom(method.returnType), "getNumberOfCommitsBetween must return text")
    }

    @Test
    fun `GitHistoryUtils still resolves the merge base of two refs`() {
        // The String-String overload, deliberately: the other one takes a GitRebaseParams.RebaseUpstream, which
        // lives in git4idea.branch — the package this integration is not allowed to import.
        val method = load("git4idea.history.GitHistoryUtils")
            .getMethod("getMergeBase", Project::class.java, VirtualFile::class.java, String::class.java, String::class.java)
            .assertNotDeprecated()
        val revision = load("git4idea.GitRevisionNumber")
        assertTrue(revision.isAssignableFrom(method.returnType), "getMergeBase must still return a GitRevisionNumber")
        assertTrue(String::class.java.isAssignableFrom(revision.getMethod("asString").assertNotDeprecated().returnType))
    }

    @Test
    fun `GitCommit still exposes the affected paths the name-status output already carried`() {
        // `getAffectedPaths()` is what keeps GitGateway cheap. The alternative, VcsFullCommitDetails.getChanges(),
        // builds a Change with content revisions per file — a different question with a much bigger bill.
        val commit = load("git4idea.GitCommit")
        val affected = commit.getMethod("getAffectedPaths").assertNotDeprecated()
        assertTrue(Set::class.java.isAssignableFrom(affected.returnType), "getAffectedPaths must return a Set of FilePath")
        assertTrue(
            load("com.intellij.vcs.log.VcsFullCommitDetails").isAssignableFrom(commit),
            "GitCommit must remain a VcsFullCommitDetails — subject/author/authorTime/fullMessage come from there.",
        )
    }

    @Test
    fun `the commit metadata GitGateway reads is still on the VCS-log interfaces`() {
        val short = load("com.intellij.vcs.log.VcsShortCommitDetails")
        assertTrue(String::class.java.isAssignableFrom(short.getMethod("getSubject").assertNotDeprecated().returnType))
        assertTrue(Long::class.javaPrimitiveType == short.getMethod("getAuthorTime").assertNotDeprecated().returnType)
        val author = short.getMethod("getAuthor").assertNotDeprecated()
        assertTrue(load("com.intellij.vcs.log.VcsUser").isAssignableFrom(author.returnType))
        val metadata = load("com.intellij.vcs.log.VcsCommitMetadata")
        assertTrue(String::class.java.isAssignableFrom(metadata.getMethod("getFullMessage").assertNotDeprecated().returnType))
        val user = load("com.intellij.vcs.log.VcsUser")
        user.getMethod("getName").assertNotDeprecated()
        user.getMethod("getEmail").assertNotDeprecated()
    }

    @Test
    fun `a commit still carries its parents, which is the only thing that makes a graph drawable`() {
        // `getParents()` is declared on `GraphCommit`, which `GitCommit` reaches through TimedVcsCommit — a
        // PUBLIC interface of the VCS-log API, deliberately: the cheap-looking alternatives on the Git side are
        // internal, and an internal API is a publication risk here, not a lint (see getAffectedPaths above).
        // Its absence would not fail loudly: every commit would arrive parentless, the branch map would decide
        // there is no topology to draw, and it would simply stop appearing with nothing anywhere saying why.
        val graphCommit = load("com.intellij.vcs.log.graph.GraphCommit")
        val parents = graphCommit.getMethod("getParents").assertNotDeprecated()
        assertTrue(List::class.java.isAssignableFrom(parents.returnType), "getParents must return a List of ids")
        assertTrue(
            graphCommit.isAssignableFrom(load("git4idea.GitCommit")),
            "GitCommit must remain a GraphCommit — the parent hashes GitGateway reads come from there.",
        )
        // The id type is what makes those parents hashes rather than integers: the graph API is generic.
        assertTrue(load("com.intellij.vcs.log.TimedVcsCommit").isAssignableFrom(load("git4idea.GitCommit")))
    }

    @Test
    fun `a GitRepository still hands over its branches, each with the commit it points at`() {
        // The refs are the other half of the branch map: parents give the shape, refs give the names. Both are
        // read from the in-memory repository model, so neither spawns `git`.
        val repository = load("git4idea.repo.GitRepository")
        val collection = load("git4idea.branch.GitBranchesCollection")
        assertTrue(collection.isAssignableFrom(repository.getMethod("getBranches").assertNotDeprecated().returnType))
        assertTrue(Collection::class.java.isAssignableFrom(collection.getMethod("getLocalBranches").assertNotDeprecated().returnType))
        assertTrue(Collection::class.java.isAssignableFrom(collection.getMethod("getRemoteBranches").assertNotDeprecated().returnType))
        // getHash takes the ABSTRACT GitBranch, so one call site serves both collections. A narrowing to the two
        // concrete types would compile here and stop compiling in GitGateway.
        val hash = collection.getMethod("getHash", load("git4idea.GitBranch")).assertNotDeprecated()
        assertTrue(load("com.intellij.vcs.log.Hash").isAssignableFrom(hash.returnType))
    }

    @Test
    fun `a branch still names itself locally, which is the spelling the map puts on a chip`() {
        // `main` and `origin/main` — the names that resolve on THIS machine. `getNameForRemoteOperations` is the
        // other spelling (`main`, for a remote branch) and putting it on a chip would draw two different refs
        // under one name.
        val reference = load("git4idea.GitReference")
        assertTrue(String::class.java.isAssignableFrom(reference.getMethod("getName").assertNotDeprecated().returnType))
        assertTrue(reference.isAssignableFrom(load("git4idea.GitBranch")))
        assertTrue(load("git4idea.GitBranch").isAssignableFrom(load("git4idea.GitLocalBranch")))
        assertTrue(load("git4idea.GitBranch").isAssignableFrom(load("git4idea.GitRemoteBranch")))
    }

    @Test
    fun `a commit id is still a Hash that can render itself as a string`() {
        val hash = load("com.intellij.vcs.log.Hash")
        assertTrue(String::class.java.isAssignableFrom(hash.getMethod("asString").assertNotDeprecated().returnType))
        val id = load("git4idea.GitCommit").getMethod("getId").assertNotDeprecated()
        assertTrue(hash.isAssignableFrom(id.returnType), "GitCommit.getId() must still return a Hash, got ${id.returnType}")
    }

    // ── platform: uncommitted changes and the hand-off to the IDE's own Git Log ────────────────────────────────

    @Test
    fun `ChangeListManager still reports every uncommitted change`() {
        val method = load("com.intellij.openapi.vcs.changes.ChangeListManager")
            .getMethod("getAllChanges")
            .assertNotDeprecated()
        assertTrue(Collection::class.java.isAssignableFrom(method.returnType))
    }

    @Test
    fun `the file-history hand-off is still three public calls, none of them deprecated`() {
        load("com.intellij.openapi.vcs.ProjectLevelVcsManager")
            .getMethod("getVcsFor", VirtualFile::class.java)
            .assertNotDeprecated()
        AbstractVcs::class.java.getMethod("getVcsHistoryProvider").assertNotDeprecated()
        load("com.intellij.openapi.vcs.AbstractVcsHelper")
            .getMethod("showFileHistory", VcsHistoryProvider::class.java, FilePath::class.java, AbstractVcs::class.java)
            .assertNotDeprecated()
        val filePath = load("com.intellij.vcsUtil.VcsUtil")
            .getMethod("getFilePath", VirtualFile::class.java)
            .assertNotDeprecated()
        assertTrue(FilePath::class.java.isAssignableFrom(filePath.returnType))
    }

    @Test
    fun `the Version Control tool window is still where the Git Log lives, and can still be activated`() {
        val toolWindow = load("com.intellij.openapi.wm.ToolWindow")
        toolWindow.getMethod("activate", Runnable::class.java, java.lang.Boolean.TYPE).assertNotDeprecated()
        val id = load("com.intellij.openapi.wm.ToolWindowId").getField("VCS").get(null)
        assertTrue(id == "Version Control", "ToolWindowId.VCS changed to '$id'; GitLogNavigator.showLog targets it by id")
    }

    /**
     * Loads a class **without running its static initializer**.
     *
     * `Class.forName(name)` initializes, and several of these classes refuse to initialize outside a running IDE:
     * `VcsUtil`'s `<clinit>` reads a file-size limit off the application's extension area and dies with an NPE
     * when `ApplicationManager.getApplication()` is null — which it is, correctly, in a pure JVM unit test. These
     * are existence-and-shape assertions; initializing was never part of the question.
     */
    private fun load(name: String): Class<*> = Class.forName(name, false, javaClass.classLoader)

    /**
     * `java.lang.Deprecated` and Kotlin's `@Deprecated` are both RUNTIME-retained, so reflection sees them.
     * `@ApiStatus.Internal` / `@ApiStatus.ScheduledForRemoval` are CLASS-retained and invisible here — that half
     * is `verifyPlugin`'s job (`INTERNAL_API_USAGES` is in its failure levels). Between the two, nothing slips.
     */
    private fun Method.assertNotDeprecated(): Method = apply {
        assertFalse(
            isAnnotationPresent(java.lang.Deprecated::class.java) || isAnnotationPresent(Deprecated::class.java),
            "$declaringClass.$name is deprecated — this repository does not ship deprecated API. Migrate before release.",
        )
    }
}
