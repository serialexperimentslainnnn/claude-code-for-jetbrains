package dev.lain.claudejb.forge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.http.HttpHeaders

class ForgeWriteTest {

    private val gitlab = ForgeRepo(ForgeProvider.GITLAB, "gitlab.com", "platform/backend", "svc")

    private val github = ForgeRepo(ForgeProvider.GITHUB, "github.com", "acme", "widget")

    private fun noHeaders(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }

    @Test
    fun `a pipeline is retried and cancelled by id, with a body-less post`() {
        val retry = GitLabApi.retryRun(gitlab, 500, "t")
        val cancel = GitLabApi.cancelRun(gitlab, 500, "t")

        assertEquals(
            "https://gitlab.com/api/v4/projects/platform%2Fbackend%2Fsvc/pipelines/500/retry",
            retry.uri.toString(),
        )
        assertEquals("POST", retry.method)
        assertNull(retry.body)
        assertEquals(true, cancel.uri.toString().endsWith("/pipelines/500/cancel"))
    }

    @Test
    fun `a workflow run is rerun and cancelled by id on GitHub's own spelling`() {
        assertEquals(
            "https://api.github.com/repos/acme/widget/actions/runs/900/rerun",
            GitHubApi.retryRun(github, 900, "t").uri.toString(),
        )
        assertEquals(
            "https://api.github.com/repos/acme/widget/actions/runs/900/cancel",
            GitHubApi.cancelRun(github, 900, "t").uri.toString(),
        )
    }

    @Test
    fun `a write request never prints its body or its headers`() {
        val request = ForgeRequest(
            GitLabApi.retryRun(gitlab, 1, "super-secret").uri,
            mapOf("PRIVATE-TOKEN" to "super-secret"),
            method = "POST",
            body = """{"sha": "cf73e32"}""",
        )

        val printed = request.toString()

        assertFalse(printed.contains("super-secret"), "the token must never reach a log")
        assertFalse(printed.contains("cf73e32"), "nor must the body it was sent with")
    }

    @Test
    fun `an accepted action is done, whatever shade of success it answered with`() {
        listOf(200, 201, 202, 204).forEach { status ->
            assertNull(ForgeHttp.refusalFor(status, noHeaders())) { "status $status" }
        }
    }

    @Test
    fun `the codes a write actually returns each say their own thing`() {
        assertEquals(ForgeRefusal.NOT_MERGEABLE, ForgeHttp.refusalFor(405, noHeaders()))
        assertEquals(ForgeRefusal.CONFLICTED, ForgeHttp.refusalFor(406, noHeaders()))
        assertEquals(ForgeRefusal.STALE, ForgeHttp.refusalFor(409, noHeaders()))
        assertEquals(ForgeRefusal.SELF_APPROVAL, ForgeHttp.refusalFor(422, noHeaders()))
    }

    @Test
    fun `a refused write says whether it was the token or you, never just forbidden`() {
        assertEquals(ForgeRefusal.TOKEN_TOO_NARROW, ForgeHttp.refusalFor(401, noHeaders()))
        assertEquals(ForgeRefusal.NO_PERMISSION, ForgeHttp.refusalFor(403, noHeaders()))
        assertEquals(
            ForgeRefusal.NO_PERMISSION,
            ForgeHttp.refusalFor(404, noHeaders()),
            "a write to something you cannot see is a permission problem, not a missing repository",
        )
    }

    @Test
    fun `an exhausted quota is rate limiting, not a permission problem`() {
        val exhausted = HttpHeaders.of(mapOf("x-ratelimit-remaining" to listOf("0"))) { _, _ -> true }

        assertEquals(ForgeRefusal.RATE_LIMITED, ForgeHttp.refusalFor(403, exhausted))
        assertEquals(ForgeRefusal.RATE_LIMITED, ForgeHttp.refusalFor(429, noHeaders()))
    }

    @Test
    fun `every refusal carries something a person can read`() {
        ForgeRefusal.entries.forEach { refusal ->
            assertFalse(refusal.note.isBlank()) { "${refusal.name} has nothing to say" }
        }
    }
}
