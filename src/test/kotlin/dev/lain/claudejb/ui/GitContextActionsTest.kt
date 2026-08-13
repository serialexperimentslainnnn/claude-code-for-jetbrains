package dev.lain.claudejb.ui

import dev.lain.claudejb.git.GitCommitInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The wording of the gear menu's Git entries — pure, no platform, no repository — plus the two contracts that
 * matter more than the wording: that the entries are actually **wired into the menu**, and that the UI layer
 * did not grow a Git write path of its own.
 */
class GitContextActionsTest {

    // ── menuText ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `no branch means no branch in the label`() {
        assertEquals("Recent Commits…", GitContextActions.menuText(null))
        assertEquals("Recent Commits…", GitContextActions.menuText(""))
        assertEquals("Recent Commits…", GitContextActions.menuText("   "))
    }

    @Test
    fun `the label names the checked-out branch`() {
        assertEquals("Recent Commits on develop…", GitContextActions.menuText("develop"))
        assertEquals("Recent Commits on develop…", GitContextActions.menuText("  develop  "))
    }

    @Test
    fun `a very long branch name is ellipsized, not left to stretch the menu`() {
        val text = GitContextActions.menuText("feature/an-extremely-long-branch-name-that-nobody-should-write")
        assertTrue(text.startsWith("Recent Commits on feature/"), text)
        assertTrue(text.contains("…"), "long branch should be cut with the shared ellipsis rule: $text")
        assertTrue(text.length < "Recent Commits on feature/an-extremely-long-branch-name-that-nobody-should-write…".length, text)
    }

    // ── popupTitle ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the chooser title carries the branch and the short head revision`() {
        assertEquals(
            "Recent commits · main · 0123456",
            GitContextActions.popupTitle("main", "0123456789abcdef0123456789abcdef01234567"),
        )
    }

    @Test
    fun `a detached or unborn head is said, not left blank`() {
        assertEquals("Recent commits · detached HEAD", GitContextActions.popupTitle(null, null))
        assertEquals("Recent commits · detached HEAD", GitContextActions.popupTitle("  ", "   "))
        assertEquals("Recent commits · main", GitContextActions.popupTitle("main", null))
    }

    // ── commitRow ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a commit row is hash, subject, author, age and file count`() {
        val row = GitContextActions.commitRow(commit(), NOW)
        assertEquals("abc1234  Fix the thing  ·  Lain  ·  3d ago  ·  2 files", row)
    }

    @Test
    fun `one changed file is singular`() {
        val row = GitContextActions.commitRow(commit(changedPaths = listOf("a.kt")), NOW)
        assertTrue(row.endsWith("·  1 file"), row)
    }

    @Test
    fun `no changed files is still a number, not an empty tail`() {
        val row = GitContextActions.commitRow(commit(changedPaths = emptyList()), NOW)
        assertTrue(row.endsWith("·  0 files"), row)
    }

    @Test
    fun `an empty commit message says so instead of leaving a gap`() {
        val row = GitContextActions.commitRow(commit(subject = "   "), NOW)
        assertTrue(row.contains("(no commit message)"), row)
    }

    @Test
    fun `the author falls back to the email, then to a placeholder`() {
        assertTrue(GitContextActions.commitRow(commit(authorName = " "), NOW).contains("lain@example.com"))
        assertTrue(GitContextActions.commitRow(commit(authorName = "", authorEmail = ""), NOW).contains("unknown author"))
    }

    @Test
    fun `a long subject is cut so one commit stays one line`() {
        val row = GitContextActions.commitRow(commit(subject = "x".repeat(200)), NOW)
        assertTrue(row.contains("…"), row)
        assertFalse(row.contains("x".repeat(100)), "the subject should have been truncated: $row")
    }

    /**
     * The row is **plain text**: a subject is content the plugin did not write, and a Swing renderer handed a
     * string that starts with `<html>` interprets the rest as markup. Passing it through verbatim — and never
     * opening an HTML document — is what keeps that impossible.
     */
    @Test
    fun `markup in a commit subject is not markup in the row`() {
        val row = GitContextActions.commitRow(commit(subject = "<b>bold</b> & <i>italic</i>"), NOW)
        assertFalse(row.startsWith("<html>"), row)
        assertTrue(row.contains("<b>bold</b> & <i>italic</i>"), row)
    }

    // ── the contracts ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The whole point of this file. A service nothing calls is a dead feature — this plugin has shipped that
     * once (the `/login` terminal lookups) — so the wiring itself is pinned, not just its behaviour.
     */
    @Test
    fun `the gear menu wires the git entries`() {
        val factory = File("src/main/kotlin/dev/lain/claudejb/ui/ClaudeToolWindowFactory.kt").readText()
        assertTrue(
            factory.contains("GitContextActions.gearEntries(project)"),
            "The tool window's gear menu no longer adds the Git entries; the git/ package is unreachable again.",
        )
    }

    /**
     * The Git integration is read-only, and `GitReadOnlyContractTest` enforces that over `git/`. This is the
     * other half: the UI must not route around the gateway by naming Git4Idea, spawning a process or reaching
     * for a mutating API. The only Git the menu may touch is the two read-only collaborators.
     */
    @Test
    fun `the ui git entries reach nothing but the read-only service and navigator`() {
        val code = File("src/main/kotlin/dev/lain/claudejb/ui/GitContextActions.kt").readLines()
            .filterNot { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")
            }
        val gitImports = code.map { it.trim() }
            .filter { it.startsWith("import dev.lain.claudejb.git.") || it.startsWith("import git4idea") }
        assertEquals(ALLOWED_GIT_IMPORTS, gitImports.toSet(), "Unexpected Git import(s) in the UI entry point: $gitImports")
        val offenders = code.flatMap { line -> FORBIDDEN_SYMBOLS.filter { it in line } }
        assertEquals(
            emptyList<String>(),
            offenders,
            "The Git menu must not run Git itself or reach a mutating Git4Idea API: $offenders",
        )
    }

    /**
     * **Absent, not greyed.** With no Git plugin and no working copy these entries can do nothing at all, and a
     * dead menu item that reports nothing is worse than a missing one. The decision is one line of `update`, and
     * a line is exactly the kind of thing a later "let's grey it out instead" turns around silently — so it is
     * pinned here, the way this repo pins the things it cannot exercise headlessly (see `JcefDependencyContractTest`).
     */
    @Test
    fun `the entries hide themselves when git is unavailable, they do not grey out`() {
        val source = File("src/main/kotlin/dev/lain/claudejb/ui/GitContextActions.kt").readLines()
            .map { it.trim() }
            .filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }
        assertTrue(
            source.any { it == "e.presentation.isVisible = history()?.isAvailable() == true" },
            "The Git entries no longer key their VISIBILITY off GitHistoryService.isAvailable().",
        )
        assertTrue(
            source.none { it.contains("presentation.isEnabled") },
            "A Git entry now greys out instead of disappearing: with no Git it would sit there doing nothing.",
        )
    }

    private fun commit(
        hash: String = "abc1234def5678",
        subject: String = "Fix the thing",
        authorName: String = "Lain",
        authorEmail: String = "lain@example.com",
        authoredAtMillis: Long = COMMITTED_AT,
        changedPaths: List<String> = listOf("a.kt", "b.kt"),
    ) = GitCommitInfo(hash, subject, authorName, authorEmail, authoredAtMillis, changedPaths)

    private companion object {
        const val COMMITTED_AT = 1_700_000_000_000L
        const val THREE_DAYS_MS = 3L * 24 * 60 * 60 * 1000
        const val NOW = COMMITTED_AT + THREE_DAYS_MS

        val ALLOWED_GIT_IMPORTS = setOf(
            "import dev.lain.claudejb.git.GitCommitInfo",
            "import dev.lain.claudejb.git.GitHistoryService",
            "import dev.lain.claudejb.git.GitLogNavigator",
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
