package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class LoginDetectionTest {

    @Test
    fun `login and auth phrasing is detected`() {
        listOf(
            "Please run `claude login` to authenticate",
            "You are not logged in.",
            "Not authenticated. Please log in.",
            "Invalid API key provided",
            "OAuth token has expired",
            "Authentication failed",
            "401 Unauthorized",
            "This is not available on this environment — use /login",
        ).forEach { assertNotEquals(AuthFailure.NONE, LoginDetection.classify(it), "should detect: $it") }
    }

    @Test
    fun `case is ignored`() {
        assertNotEquals(AuthFailure.NONE, LoginDetection.classify("PLEASE LOG IN"))
        assertNotEquals(AuthFailure.NONE, LoginDetection.classify("Invalid API Key"))
        assertEquals(
            AuthFailure.EXPIRED,
            LoginDetection.classify("401 OAUTH ACCESS TOKEN HAS EXPIRED"),
        )
    }

    @Test
    fun `billing and quota errors are NOT a login problem`() {
        listOf(
            "Your credit balance is too low to access the API",
            "You have hit your rate limit",
            "Weekly quota exceeded",
            "You are using overage",
            "Usage limit reached for this window",
        ).forEach { assertEquals(AuthFailure.NONE, LoginDetection.classify(it), "should NOT prompt login: $it") }
    }

    @Test
    fun `unrelated errors do not trigger`() {
        assertEquals(AuthFailure.NONE, LoginDetection.classify("Tool execution failed: file not found"))
        assertEquals(AuthFailure.NONE, LoginDetection.classify("Connection reset by peer"))
    }

    @Test
    fun `exclusion wins even if a login hint is also present`() {
        assertEquals(AuthFailure.NONE, LoginDetection.classify("Credit balance too low; you are still logged in"))
    }

    @Test
    fun `the reported 401 is an expired access token, not a missing identity`() {
        assertEquals(AuthFailure.EXPIRED, LoginDetection.classify(EXPIRY_401))
    }

    @Test
    fun `the same expiry said shorter is still an expiry`() {
        assertEquals(AuthFailure.EXPIRED, LoginDetection.classify("OAuth token has expired"))
    }

    @Test
    fun `an expired refresh token is a missing identity`() {
        assertEquals(
            AuthFailure.NO_IDENTITY,
            LoginDetection.classify("OAuth refresh token has expired. Please log in again."),
        )
    }

    @Test
    fun `texts that name no identity ask for a sign-in`() {
        listOf(
            "Please run `claude login` to authenticate",
            "You are not logged in.",
            "Not authenticated. Please log in.",
            "Invalid API key provided",
            "401 Unauthorized",
        ).forEach { assertEquals(AuthFailure.NO_IDENTITY, LoginDetection.classify(it), "should ask login: $it") }
    }

    @Test
    fun `expiry wording outside an auth failure is not an auth failure`() {
        assertEquals(AuthFailure.NONE, LoginDetection.classify("Your trial has expired"))
        assertEquals(AuthFailure.NONE, LoginDetection.classify("The download token expired"))
    }

    @Test
    fun `billing wording stays NONE whichever kind it resembles`() {
        assertEquals(AuthFailure.NONE, LoginDetection.classify("Credit balance too low; oauth token expired"))
    }

    @Test
    fun `null and blank classify as NONE`() {
        assertEquals(AuthFailure.NONE, LoginDetection.classify(null))
        assertEquals(AuthFailure.NONE, LoginDetection.classify(""))
        assertEquals(AuthFailure.NONE, LoginDetection.classify("   "))
    }

    @Test
    fun `null and blank resolve to NONE whatever the vault holds`() {
        listOf(true, false).forEach { renewable ->
            assertEquals(AuthFailure.NONE, LoginDetection.resolve(null) { renewable })
            assertEquals(AuthFailure.NONE, LoginDetection.resolve("") { renewable })
            assertEquals(AuthFailure.NONE, LoginDetection.resolve("   ") { renewable })
        }
    }

    @Test
    fun `a renewable access-token expiry stays an expiry`() {
        assertEquals(AuthFailure.EXPIRED, LoginDetection.resolve(EXPIRY_401) { true })
        assertEquals(AuthFailure.EXPIRED, LoginDetection.resolve("OAuth token has expired") { true })
    }

    @Test
    fun `an expiry with nothing to renew is a missing identity`() {
        assertEquals(AuthFailure.NO_IDENTITY, LoginDetection.resolve(EXPIRY_401) { false })
        assertEquals(AuthFailure.NO_IDENTITY, LoginDetection.resolve("OAuth token has expired") { false })
    }

    @Test
    fun `renewability is not asked unless the text is an access-token expiry`() {
        var asked = 0
        listOf(
            "Tool execution failed: file not found",
            "Your credit balance is too low to access the API",
            "You are not logged in.",
            "OAuth refresh token has expired. Please log in again.",
            null,
            "",
        ).forEach { text ->
            LoginDetection.resolve(text) {
                asked++
                true
            }
        }
        assertEquals(0, asked, "the credential safe must not be consulted for these")
    }

    @Test
    fun `renewability changes nothing outside an access-token expiry`() {
        listOf(true, false).forEach { renewable ->
            assertEquals(AuthFailure.NONE, LoginDetection.resolve("Connection reset by peer") { renewable })
            assertEquals(AuthFailure.NONE, LoginDetection.resolve("Weekly quota exceeded") { renewable })
            assertEquals(AuthFailure.NO_IDENTITY, LoginDetection.resolve("401 Unauthorized") { renewable })
            assertEquals(AuthFailure.NO_IDENTITY, LoginDetection.resolve("Invalid API key provided") { renewable })
            assertEquals(
                AuthFailure.NO_IDENTITY,
                LoginDetection.resolve("OAuth refresh token has expired. Please log in again.") { renewable },
            )
        }
    }

    @Test
    fun `no auth failure is ever swallowed, and nothing else is ever raised as one`() {
        val authFailures = listOf(
            EXPIRY_401,
            "OAuth token has expired",
            "OAuth refresh token has expired. Please log in again.",
            "Please run `claude login` to authenticate",
            "You are not logged in.",
            "Not authenticated. Please log in.",
            "Invalid API key provided",
            "Authentication failed",
            "401 Unauthorized",
            "This is not available on this environment — use /login",
        )
        val notAuthFailures = listOf(
            "Your credit balance is too low to access the API",
            "You have hit your rate limit",
            "Weekly quota exceeded",
            "Credit balance too low; you are still logged in",
            "Tool execution failed: file not found",
            "Connection reset by peer",
            "Your trial has expired",
        )
        listOf(true, false).forEach { renewable ->
            authFailures.forEach {
                assertNotEquals(AuthFailure.NONE, LoginDetection.classify(it), "still an auth failure: $it")
                assertNotEquals(
                    AuthFailure.NONE,
                    LoginDetection.resolve(it) { renewable },
                    "resolve must not swallow an auth failure: $it",
                )
            }
            notAuthFailures.forEach {
                assertEquals(AuthFailure.NONE, LoginDetection.classify(it), "should classify as NONE: $it")
                assertEquals(
                    AuthFailure.NONE,
                    LoginDetection.resolve(it) { renewable },
                    "should not be raised as an auth failure: $it",
                )
            }
        }
    }

    private companion object {
        const val EXPIRY_401 =
            "Failed to authenticate. API Error: 401 OAuth access token has expired. Re-authenticate to continue."
    }
}
