package dev.lain.claudejb.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

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

    private fun codeLines(file: File): List<String> = file.readLines().filterNot { line ->
        val trimmed = line.trimStart()
        trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")
    }

    private companion object {

        const val GATEWAY = "GitGateway.kt"

        val GIT4IDEA_REFERENCE = Regex("""\bgit4idea\.[A-Za-z]""")

        val READ_ONLY_GIT_API = setOf(
            "git4idea.GitCommit",
            "git4idea.GitRevisionNumber",
            "git4idea.branch.GitBranchesCollection",
            "git4idea.history.GitHistoryUtils",
            "git4idea.repo.GitBranchTrackInfo",
            "git4idea.repo.GitRemote",
            "git4idea.repo.GitRepository",
            "git4idea.repo.GitRepositoryChangeListener",
            "git4idea.repo.GitRepositoryManager",
        )

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
