package dev.lain.claudejb.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class GitCommitInfoTest {

    @Test
    fun `a full hash is abbreviated to git's own seven characters`() {
        assertEquals("cf73e32", GitCommitInfo.shortHash("cf73e32b9a1d4e5f60718293a4b5c6d7e8f90123"))
    }

    @Test
    fun `a hash already at or below the abbreviation length is returned untouched`() {
        assertEquals("cf73e32", GitCommitInfo.shortHash("cf73e32"))
        assertEquals("cf73", GitCommitInfo.shortHash("cf73"))
        assertEquals("", GitCommitInfo.shortHash(""))
    }

    @Test
    fun `the property and the helper cannot disagree`() {
        val commit = commit(hash = "0123456789abcdef")
        assertEquals(GitCommitInfo.shortHash(commit.hash), commit.shortHash)
    }

    @Test
    fun `the subject is the first non-blank line, trimmed`() {
        assertEquals(
            "fix(ui): stop the readout shuffling on every tick",
            GitCommitInfo.subjectOf("fix(ui): stop the readout shuffling on every tick\n\nA body nobody wants in a list.\n"),
        )
    }

    @Test
    fun `leading blank lines are skipped rather than returned as an empty subject`() {
        assertEquals("docs(changelog): record 5.5.0", GitCommitInfo.subjectOf("\n\n   docs(changelog): record 5.5.0  \nbody"))
    }

    @Test
    fun `a message that is entirely blank yields an empty subject, not a crash`() {
        assertEquals("", GitCommitInfo.subjectOf(""))
        assertEquals("", GitCommitInfo.subjectOf("\n \t\n"))
    }

    @Test
    fun `a path under the root loses the root prefix`() {
        assertEquals(
            "src/main/kotlin/dev/lain/claudejb/git/GitGateway.kt",
            GitCommitInfo.relativize("/home/u/project", "/home/u/project/src/main/kotlin/dev/lain/claudejb/git/GitGateway.kt"),
        )
    }

    @Test
    fun `a trailing slash on the root is tolerated`() {
        assertEquals("README.md", GitCommitInfo.relativize("/home/u/project/", "/home/u/project/README.md"))
    }

    @Test
    fun `a sibling directory sharing the root's name prefix is NOT stripped`() {
        assertEquals("/home/u/project-notes/todo.md", GitCommitInfo.relativize("/home/u/project", "/home/u/project-notes/todo.md"))
    }

    @Test
    fun `a path outside the root is returned unchanged rather than mangled`() {
        assertEquals("/etc/hosts", GitCommitInfo.relativize("/home/u/project", "/etc/hosts"))
    }

    @Test
    fun `the root itself is not turned into an empty string`() {
        assertEquals("/home/u/project", GitCommitInfo.relativize("/home/u/project", "/home/u/project"))
    }

    @Test
    fun `an empty root leaves every path absolute`() {
        assertEquals("/home/u/project/a.kt", GitCommitInfo.relativize("", "/home/u/project/a.kt"))
        assertEquals("/home/u/project/a.kt", GitCommitInfo.relativize("/", "/home/u/project/a.kt"))
    }

    @Test
    fun `windows paths relativize too, because the VFS uses forward slashes on every OS`() {
        assertEquals("src/App.kt", GitCommitInfo.relativize("C:/Users/u/project", "C:/Users/u/project/src/App.kt"))
    }

    @Test
    fun `two commits with the same fields are the same commit`() {
        val a = commit(hash = "cf73e32b9a1d")
        val b = commit(hash = "cf73e32b9a1d")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(a.toString(), b.toString())
        assertNotEquals(a, a.copy(subject = "something else"))
    }

    @Test
    fun `the model carries exactly what a commit row needs`() {
        // constructor silently swaps two strings, and detekt rejects them past three components anyway.
        val info = GitCommitInfo(
            hash = "0123456789abcdef",
            subject = "feat(git): read the branch and the recent commits",
            authorName = "Lain",
            authorEmail = "lain@example.invalid",
            authoredAtMillis = 1_754_900_000_000L,
            changedPaths = listOf("build.gradle.kts", "src/main/kotlin/dev/lain/claudejb/git/GitGateway.kt"),
        )
        assertEquals("0123456789abcdef", info.hash)
        assertEquals("0123456", info.shortHash)
        assertEquals("feat(git): read the branch and the recent commits", info.subject)
        assertEquals("Lain", info.authorName)
        assertEquals("lain@example.invalid", info.authorEmail)
        assertEquals(1_754_900_000_000L, info.authoredAtMillis)
        assertEquals(listOf("build.gradle.kts", "src/main/kotlin/dev/lain/claudejb/git/GitGateway.kt"), info.changedPaths)
    }

    private fun commit(hash: String) = GitCommitInfo(
        hash = hash,
        subject = "subject",
        authorName = "Lain",
        authorEmail = "lain@example.invalid",
        authoredAtMillis = 0L,
        changedPaths = emptyList(),
    )
}
