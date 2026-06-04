package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins [LoginDetection.needsLogin]: auth/login phrasing → true, billing/quota or unrelated errors → false,
 * and blank/null → false. This gates whether the session offers the "log in in a terminal" prompt.
 */
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
        ).forEach { assertTrue(LoginDetection.needsLogin(it), "should detect: $it") }
    }

    @Test
    fun `case is ignored`() {
        assertTrue(LoginDetection.needsLogin("PLEASE LOG IN"))
        assertTrue(LoginDetection.needsLogin("Invalid API Key"))
    }

    @Test
    fun `billing and quota errors are NOT a login problem`() {
        listOf(
            "Your credit balance is too low to access the API",
            "You have hit your rate limit",
            "Weekly quota exceeded",
            "You are using overage",
            "Usage limit reached for this window",
        ).forEach { assertFalse(LoginDetection.needsLogin(it), "should NOT prompt login: $it") }
    }

    @Test
    fun `unrelated errors do not trigger`() {
        assertFalse(LoginDetection.needsLogin("Tool execution failed: file not found"))
        assertFalse(LoginDetection.needsLogin("Connection reset by peer"))
    }

    @Test
    fun `blank or null is false`() {
        assertFalse(LoginDetection.needsLogin(null))
        assertFalse(LoginDetection.needsLogin(""))
        assertFalse(LoginDetection.needsLogin("   "))
    }

    @Test
    fun `exclusion wins even if a login hint is also present`() {
        // A message that mentions both quota and login should not nag about login — it's a billing issue.
        assertFalse(LoginDetection.needsLogin("Credit balance too low; you are still logged in"))
    }
}
