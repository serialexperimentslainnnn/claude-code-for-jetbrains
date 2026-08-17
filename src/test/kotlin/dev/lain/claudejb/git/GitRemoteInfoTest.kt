package dev.lain.claudejb.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The remote-URL parse, on a bare JVM: no IDE, no repository, no network.
 *
 * Every shape here is one Git genuinely writes into `.git/config`, and the ones that matter are the ones that are
 * NOT URLs — scp syntax (`git@host:owner/repo.git`) has no scheme and uses the colon as a path separator, so a real
 * URL parser reads `owner/repo.git` as the port. The rest of the cases are the ways the same URL varies in the
 * wild: with and without `.git`, with a port, with credentials, nested GitLab groups, a self-hosted host, and a
 * remote that is a directory on this machine.
 */
class GitRemoteInfoTest {

    // ── https ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an https GitHub url yields provider, owner and repo`() {
        val remote = GitRemoteInfo.parse("https://github.com/anthropics/claude-code.git")
        assertEquals(GitRemoteProvider.GITHUB, remote.provider)
        assertEquals("anthropics", remote.owner)
        assertEquals("claude-code", remote.repo)
        assertEquals("https://github.com/anthropics/claude-code.git", remote.url)
    }

    @Test
    fun `the dot-git suffix is optional, which is how the browser's copy button writes it`() {
        val remote = GitRemoteInfo.parse("https://github.com/anthropics/claude-code")
        assertEquals(GitRemoteProvider.GITHUB, remote.provider)
        assertEquals("anthropics", remote.owner)
        assertEquals("claude-code", remote.repo)
    }

    @Test
    fun `a trailing slash is not a fourth path segment`() {
        assertEquals("claude-code", GitRemoteInfo.parse("https://github.com/anthropics/claude-code/").repo)
    }

    @Test
    fun `credentials in the url do not become the host, and never reach the parsed fields`() {
        // A token pasted into the remote URL is a real (bad) habit. Whatever else happens, it must not be mistaken
        // for the hostname, or the provider is decided on a secret.
        val remote = GitRemoteInfo.parse("https://oauth2:glpat-secret@gitlab.com/group/repo.git")
        assertEquals(GitRemoteProvider.GITLAB, remote.provider)
        assertEquals("group", remote.owner)
        assertEquals("repo", remote.repo)
    }

    // ── ssh, both spellings ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `scp syntax is not a url and is parsed as its own shape`() {
        val remote = GitRemoteInfo.parse("git@github.com:anthropics/claude-code.git")
        assertEquals(GitRemoteProvider.GITHUB, remote.provider)
        assertEquals("anthropics", remote.owner)
        assertEquals("claude-code", remote.repo)
    }

    @Test
    fun `an ssh url with an explicit port keeps the port out of the host`() {
        // The port is exactly where scp syntax and ssh URLs disagree, and reading it as part of the hostname is
        // what turns `github.com:22` into an unknown provider.
        val remote = GitRemoteInfo.parse("ssh://git@github.com:22/anthropics/claude-code.git")
        assertEquals(GitRemoteProvider.GITHUB, remote.provider)
        assertEquals("anthropics", remote.owner)
        assertEquals("claude-code", remote.repo)
    }

    @Test
    fun `the git protocol is a scheme like any other`() {
        val remote = GitRemoteInfo.parse("git://github.com/anthropics/claude-code.git")
        assertEquals(GitRemoteProvider.GITHUB, remote.provider)
        assertEquals("anthropics", remote.owner)
    }

    // ── provider detection ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a self-hosted GitLab is recognised by its host label, not by the dot-com`() {
        val remote = GitRemoteInfo.parse("git@gitlab.example.com:platform/tooling/deploy.git")
        assertEquals(GitRemoteProvider.GITLAB, remote.provider)
        assertEquals("platform/tooling", remote.owner)
        assertEquals("deploy", remote.repo)
    }

    @Test
    fun `a nested GitLab group keeps every level of the namespace`() {
        // Losing a level here is not cosmetic: `group/repo` is a different project from `group/sub/repo`, and both
        // exist.
        val remote = GitRemoteInfo.parse("https://gitlab.com/a/b/c/thing.git")
        assertEquals("a/b/c", remote.owner)
        assertEquals("thing", remote.repo)
    }

    @Test
    fun `a host that merely contains the word is not the provider`() {
        // The whole reason detection is per dot-separated label: a substring test says GitHub to this one.
        val remote = GitRemoteInfo.parse("https://mygithub.example.org/team/app.git")
        assertEquals(GitRemoteProvider.OTHER, remote.provider)
        assertEquals("team", remote.owner)
        assertEquals("app", remote.repo)
    }

    @Test
    fun `a self-hosted forge under a neutral name is OTHER, and that is the honest answer`() {
        assertEquals(GitRemoteProvider.OTHER, GitRemoteInfo.parse("git@git.example.com:team/app.git").provider)
    }

    @Test
    fun `the host is matched case-insensitively, because DNS is`() {
        assertEquals(GitRemoteProvider.GITHUB, GitRemoteInfo.parse("https://GitHub.com/Anthropics/Claude-Code.git").provider)
        assertEquals("Anthropics", GitRemoteInfo.parse("https://GitHub.com/Anthropics/Claude-Code.git").owner)
    }

    // ── local remotes and rubbish ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a local path has a repository but no owner`() {
        // `/srv/git` is a directory, not a namespace. Reporting it as the owner would put it where a UI expects an
        // organisation, which reads as a fact and is not one.
        val remote = GitRemoteInfo.parse("/srv/git/mirror.git")
        assertEquals(GitRemoteProvider.OTHER, remote.provider)
        assertNull(remote.owner)
        assertEquals("mirror", remote.repo)
    }

    @Test
    fun `a relative remote is a local remote too`() {
        val remote = GitRemoteInfo.parse("../sibling")
        assertNull(remote.owner)
        assertEquals("sibling", remote.repo)
    }

    @Test
    fun `a windows path is not a host called C`() {
        // The colon here is a drive letter, and treating it as the scp separator makes `C` the hostname and
        // everything after it a namespace.
        val remote = GitRemoteInfo.parse("C:/repos/thing")
        assertEquals(GitRemoteProvider.OTHER, remote.provider)
        assertNull(remote.owner)
        assertEquals("thing", remote.repo)
    }

    @Test
    fun `an empty or blank url parses to nothing at all rather than throwing`() {
        listOf("", "   ", "\n\t").forEach { raw ->
            val remote = GitRemoteInfo.parse(raw)
            assertEquals("", remote.url, "surrounding whitespace is not part of the URL")
            assertEquals(GitRemoteProvider.OTHER, remote.provider)
            assertNull(remote.owner)
            assertNull(remote.repo)
        }
    }

    @Test
    fun `a host with nothing after it yields neither owner nor repository`() {
        val remote = GitRemoteInfo.parse("https://github.com/")
        assertEquals(GitRemoteProvider.GITHUB, remote.provider)
        assertNull(remote.owner)
        assertNull(remote.repo)
    }

    @Test
    fun `rubbish is carried through verbatim instead of being guessed at`() {
        // The parse is faithful, not clever: what it cannot read as a host it reads as a path, and the URL itself
        // is always intact for a caller to show. Nothing here throws, because a malformed remote in someone's
        // config must not be able to take the Git view down.
        val junk = GitRemoteInfo.parse("::::")
        assertEquals("::::", junk.url)
        assertEquals(GitRemoteProvider.OTHER, junk.provider)
        assertNull(junk.owner)

        val prose = GitRemoteInfo.parse("not a url at all")
        assertEquals(GitRemoteProvider.OTHER, prose.provider)
        assertNull(prose.owner)
        assertEquals("not a url at all", prose.repo)
    }

    @Test
    fun `the surrounding whitespace of a hand-edited config is trimmed`() {
        val remote = GitRemoteInfo.parse("  https://github.com/anthropics/claude-code.git  ")
        assertEquals("https://github.com/anthropics/claude-code.git", remote.url)
        assertEquals("anthropics", remote.owner)
    }

    // ── value semantics ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `two parses of the same url are the same value`() {
        // Same reason as GitCommitInfo: the dashboard re-pushes its model on every tick and skips the repaint when
        // nothing changed. Equality on the model is what that skip is.
        val url = "git@github.com:anthropics/claude-code.git"
        assertEquals(GitRemoteInfo.parse(url), GitRemoteInfo.parse(url))
        assertEquals(GitRemoteInfo.parse(url).hashCode(), GitRemoteInfo.parse(url).hashCode())
    }
}
