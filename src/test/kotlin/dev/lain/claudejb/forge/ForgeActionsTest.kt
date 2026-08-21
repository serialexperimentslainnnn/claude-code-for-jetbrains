package dev.lain.claudejb.forge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ForgeActionsTest {

    private val gitlab = ForgeRepo(ForgeProvider.GITLAB, "gitlab.com", "platform/backend", "svc")

    private val github = ForgeRepo(ForgeProvider.GITHUB, "github.com", "acme", "widget")

    @Test
    fun `GitLab acts on a merge request by its iid, with the verb in the path`() {
        val root = "https://gitlab.com/api/v4/projects/platform%2Fbackend%2Fsvc/merge_requests/7"

        assertEquals("$root/approve", GitLabApi.approve(gitlab, 7, "t").uri.toString())
        assertEquals("$root/unapprove", GitLabApi.unapprove(gitlab, 7, "t")!!.uri.toString())
        assertEquals("$root/merge", GitLabApi.merge(gitlab, 7, "t").uri.toString())
        assertEquals("PUT", GitLabApi.merge(gitlab, 7, "t").method, "GitLab merges with a PUT")
    }

    @Test
    fun `GitHub approves by filing a review, because it has no approve endpoint`() {
        val request = GitHubApi.approve(github, 42, "t")

        assertEquals("https://api.github.com/repos/acme/widget/pulls/42/reviews", request.uri.toString())
        assertEquals("POST", request.method)
        assertTrue(request.body!!.contains("APPROVE"))
    }

    @Test
    fun `GitHub cannot simply withdraw an approval, and says so rather than pretending`() {
        assertNull(
            GitHubApi.unapprove(github, 42, "t"),
            "dismissing a review needs the review's own id, which is not the same gesture",
        )
        assertNotNull(GitLabApi.unapprove(gitlab, 7, "t"))
    }

    @Test
    fun `a comment goes where each forge keeps them, and carries the text as a body`() {
        val gl = GitLabApi.comment(gitlab, 7, "looks good", "t")
        val gh = GitHubApi.comment(github, 42, "looks good", "t")

        assertTrue(gl.uri.toString().endsWith("/merge_requests/7/notes"))
        assertTrue(gh.uri.toString().endsWith("/issues/42/comments"), "GitHub keeps pull comments with issues")
        assertTrue(gl.body!!.contains("looks good"))
        assertTrue(gh.body!!.contains("looks good"))
        assertEquals("application/json", gl.headers["Content-Type"])
    }

    @Test
    fun `text that could break the request is carried as data, not pasted into it`() {
        val hostile = """he said "ship it" \ then left"""

        val body = GitLabApi.comment(gitlab, 7, hostile, "t").body!!

        assertTrue(body.contains("\\\""), "the quotes are escaped rather than closing the field")
        assertTrue(body.startsWith("{") && body.endsWith("}"))
    }

    @Test
    fun `opening a request names both ends, in the words each forge uses`() {
        val gl = GitLabApi.openPullRequest(gitlab, "feature/x", "main", "Add the thing", "t")
        val gh = GitHubApi.openPullRequest(github, "feature/x", "main", "Add the thing", "t")

        assertTrue(gl.uri.toString().endsWith("/merge_requests"))
        assertTrue(gl.body!!.contains("source_branch") && gl.body!!.contains("target_branch"))
        assertTrue(gh.uri.toString().endsWith("/pulls"))
        assertTrue(gh.body!!.contains("\"head\"") && gh.body!!.contains("\"base\""))
    }

    @Test
    fun `no action ever puts the token anywhere a log could reach`() {
        val requests = listOf(
            GitLabApi.approve(gitlab, 7, "super-secret"),
            GitLabApi.merge(gitlab, 7, "super-secret"),
            GitLabApi.comment(gitlab, 7, "hi", "super-secret"),
            GitLabApi.openPullRequest(gitlab, "a", "b", "t", "super-secret"),
            GitHubApi.approve(github, 42, "super-secret"),
            GitHubApi.merge(github, 42, "super-secret"),
            GitHubApi.comment(github, 42, "hi", "super-secret"),
            GitHubApi.openPullRequest(github, "a", "b", "t", "super-secret"),
        )

        requests.forEach { request ->
            assertTrue("super-secret" !in request.uri.toString()) { "token in the URL: $request" }
            assertTrue("super-secret" !in request.toString()) { "token in the printed form: $request" }
            assertTrue("super-secret" !in request.body.orEmpty()) { "token in the body" }
        }
    }
}
