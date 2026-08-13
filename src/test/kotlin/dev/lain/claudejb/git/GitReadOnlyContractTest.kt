package dev.lain.claudejb.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * **The Git integration is read-only, and this is what makes that true rather than merely written down.**
 *
 * The decision it enforces was taken before a line was written, and it is not a preference:
 *  - **no `git reset`, no history rewriting** — the plugin never moves a ref the user did not move. Undoing a
 *    change is a NEW commit, made by the user through the IDE's own Git UI;
 *  - **remotes are read-only** — no push, no fetch-and-merge behind the user's back;
 *  - **`pull` / `rebase` / `merge` are local operations that need an explicit human yes** — which means they are
 *    the IDE's actions, not ours.
 *
 * A comment saying so ages badly; a *test* saying so fails the build. The mechanism is an **allowlist**, not a
 * denylist: the only `git4idea` imports permitted in this package are the four read-only ones below, so adding a
 * write path is not something that can happen by accident during a refactor — it takes editing this list, in a
 * diff a reviewer reads.
 *
 * The second contract here is the **degradation** one. `git4idea` may be entirely absent from this plugin's
 * classloader (the dependency is optional), and a class's supertypes and annotations are resolved EAGERLY at
 * load time while the classes named inside method bodies are not. Keeping every git4idea reference inside one
 * object — reached only behind [GitAvailability] — is what turns "Git is disabled" into an empty list instead of
 * a `NoClassDefFoundError` in the middle of a chat.
 *
 * Comment and KDoc lines are skipped — a line whose trimmed form starts with an asterisk, a double slash, or a
 * block-comment opener — because this very file's neighbours discuss `git4idea`, `reset` and `rebase` in prose;
 * the scan is over code. (Spelled out rather than shown: a literal block-comment opener inside KDoc nests, and
 * Kotlin then reports an unclosed comment for the whole file.)
 */
class GitReadOnlyContractTest {

    private val sources: List<File> = File("src/main/kotlin/dev/lain/claudejb/git")
        .listFiles()
        ?.filter { it.isFile && it.extension == "kt" }
        ?.sortedBy { it.name }
        .orEmpty()

    @Test
    fun `the package exists and this test is actually looking at it`() {
        assertTrue(sources.isNotEmpty(), "No Kotlin sources found under src/main/kotlin/dev/lain/claudejb/git")
        assertTrue(sources.any { it.name == GATEWAY }, "$GATEWAY is missing; the containment this test pins is gone")
    }

    @Test
    fun `only the gateway names a git4idea type`() {
        val offenders = sources
            .filter { it.name != GATEWAY }
            .filter { file -> codeLines(file).any { GIT4IDEA_REFERENCE.containsMatchIn(it) } }
            .map { it.name }
        assertEquals(
            emptyList<String>(),
            offenders,
            "These files name a git4idea type outside $GATEWAY: $offenders. That breaks the degradation " +
                "guarantee — with the Git plugin disabled those classes are not on our classpath at all.",
        )
    }

    @Test
    fun `every git4idea import is on the read-only allowlist`() {
        val imports = sources
            .flatMap { file -> codeLines(file).map { file.name to it.trim() } }
            .filter { (_, line) -> line.startsWith("import git4idea") }
            .map { (file, line) -> file to line.removePrefix("import ").substringBefore(" as ").trim() }
        assertTrue(imports.isNotEmpty(), "No git4idea import found at all — has the integration been gutted?")
        val forbidden = imports.filter { (_, fqn) -> fqn !in READ_ONLY_GIT_API }
        assertEquals(
            emptyList<Pair<String, String>>(),
            forbidden,
            "Non-allowlisted git4idea import(s): $forbidden. The Git integration is READ-ONLY: no reset, no " +
                "history rewriting, no remote writes, and no local pull/rebase/merge without an explicit user " +
                "confirmation — which means those belong to the IDE's own Git UI, not to this plugin. If a new " +
                "read-only API is genuinely needed, add it to READ_ONLY_GIT_API deliberately.",
        )
    }

    @Test
    fun `nothing in the package runs git itself, or reaches a mutating Git4Idea entry point`() {
        val hits = sources.flatMap { file ->
            codeLines(file).flatMap { line -> FORBIDDEN_SYMBOLS.filter { it in line }.map { "${file.name}: $it" } }
        }
        assertEquals(
            emptyList<String>(),
            hits,
            "Forbidden symbol(s) in the git package: $hits. Reading history goes through GitHistoryUtils and the " +
                "repository model; spawning `git` ourselves, or calling Git4Idea's command/branch machinery, is " +
                "how a read-only integration quietly becomes a write one.",
        )
    }

    /** Code lines only: KDoc and comments in this package discuss git4idea, reset and rebase in prose. */
    private fun codeLines(file: File): List<String> = file.readLines().filterNot { line ->
        val trimmed = line.trimStart()
        trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")
    }

    private companion object {

        const val GATEWAY = "GitGateway.kt"

        /** Any `git4idea.Something` reference, qualified — an import or an inline fully-qualified name. */
        val GIT4IDEA_REFERENCE = Regex("""\bgit4idea\.[A-Za-z]""")

        /**
         * The complete set of Git4Idea API this plugin may touch. All four are pure readers: the repository
         * registry, one repository, and the `git log` reader plus the commit it returns. Nothing here can change
         * a ref, a worktree or a remote.
         */
        val READ_ONLY_GIT_API = setOf(
            "git4idea.GitCommit",
            "git4idea.history.GitHistoryUtils",
            "git4idea.repo.GitRepository",
            "git4idea.repo.GitRepositoryManager",
        )

        /**
         * Symbols that would mean the plugin had grown its own way to run Git. The first three are process
         * spawning in any form; the rest are Git4Idea's own execution and branch-manipulation surface — the
         * things `git4idea.commands` / `git4idea.branch` exist to do.
         */
        val FORBIDDEN_SYMBOLS = listOf(
            "GeneralCommandLine",
            "ProcessBuilder",
            "Runtime.getRuntime",
            "GitLineHandler",
            "GitCommand",
            "GitBrancher",
            "GitImpl",
            "GitCheckinEnvironment",
        )
    }
}
