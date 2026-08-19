package dev.lain.claudejb.forge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class ForgeSecrecyTest {

    private val token = "ghp_SECRETsecretSECRET0123456789"
    private val github = ForgeRepo(ForgeProvider.GITHUB, "github.com", "acme", "widget")
    private val gitlab = ForgeRepo(ForgeProvider.GITLAB, "gitlab.com", "acme", "widget")

    @Test
    fun `a request prints its URI and never its headers`() {
        val printed = GitHubApi.pullRequests(github, "main", token).toString()

        assertFalse(token in printed) {
            "ForgeRequest.toString() leaked the token. It must not become a data class, and toString() must " +
                "name the URI only."
        }
        assertTrue("github.com" in printed) { printed }
    }

    @Test
    fun `no URL this package builds carries the token`() {
        val urls = listOf(
            GitHubApi.pullRequests(github, "main", token).uri,
            GitHubApi.latestRun(github, "main", token).uri,
            GitLabApi.pullRequests(gitlab, "main", token).uri,
            GitLabApi.latestRun(gitlab, "main", token).uri,
        )

        urls.forEach { uri -> assertFalse(token in uri.toString()) { "token in the URL: $uri" } }
    }

    @Test
    fun `the header is the one place it lives, and only the one the provider expects`() {
        val githubHeaders = GitHubApi.pullRequests(github, "main", token).headers
        val gitlabHeaders = GitLabApi.pullRequests(gitlab, "main", token).headers

        assertEquals("Bearer $token", githubHeaders["Authorization"])
        assertEquals(token, gitlabHeaders["PRIVATE-TOKEN"])
        assertFalse("Authorization" in gitlabHeaders)
        assertFalse("PRIVATE-TOKEN" in githubHeaders)
    }

    @Test
    fun `every failure that can reach the UI is a token-free value`() {
        val failures = buildList<ForgeAnswer<*>> {
            ForgeSilence.entries.forEach { reason -> add(ForgeAnswer.Silent(reason)) }
            add(ForgeHttp.fetch(ForgeRequest(URI.create("http://never.invalid/x"), mapOf("Authorization" to token))))
        }

        failures.forEach { failure ->
            assertFalse(token in failure.toString()) { "a failure value leaked the token: $failure" }
        }
    }
}
