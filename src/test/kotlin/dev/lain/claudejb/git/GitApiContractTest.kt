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
 * one runs against the build classpath in milliseconds and fails the moment JetBrains deprecates one of these
 * twelve members — which is when the migration is cheap, not when the release is being cut.
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

    // ── git4idea: the log ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `GitHistoryUtils history still takes a root plus raw git-log parameters`() {
        // The vararg IS the contract: it is how `-n <limit>` reaches `git log`. A signature change here would
        // silently become "history() with no limit" — the whole repository, on every call.
        val method = load("git4idea.history.GitHistoryUtils")
            .getMethod("history", Project::class.java, VirtualFile::class.java, Array<String>::class.java)
            .assertNotDeprecated()
        assertTrue(List::class.java.isAssignableFrom(method.returnType), "history() must return a List of commits")
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
