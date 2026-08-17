package dev.lain.claudejb.forge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * **The access token exists in exactly one place: the value of one request header.**
 *
 * Everything else in the package is a value that can be interpolated into a log line, an exception message,
 * an assertion failure or a debugger label without anybody deciding to print a credential — which is how a
 * credential gets printed. The pins below are cheap and the defect they prevent is not recoverable: a token
 * in `idea.log` is a token in whatever the user attaches to a bug report.
 *
 * The forbidden shapes, each with the way it would actually happen:
 *  - a `data class` holding the headers, whose generated `toString` prints them (this is why [ForgeRequest]
 *    is not one);
 *  - the token in the URL, where it would reach the IDE's own HTTP logging and every proxy in between;
 *  - the token in a failure value, which is the thing that travels back up through the UI.
 */
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
        // Not a style check: GitLab rejects a personal access token presented as `Authorization: Bearer`,
        // which it reserves for OAuth. Sending both would also be sending the credential twice.
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
            // The transport's own refusal, produced without a socket: the scheme is checked before sending.
            add(ForgeHttp.fetch(ForgeRequest(URI.create("http://never.invalid/x"), mapOf("Authorization" to token))))
        }

        failures.forEach { failure ->
            assertFalse(token in failure.toString()) { "a failure value leaked the token: $failure" }
        }
    }
}
