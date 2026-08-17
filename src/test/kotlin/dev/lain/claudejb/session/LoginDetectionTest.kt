package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Pins [LoginDetection]: which kind of authentication failure the wording alone describes
 * ([LoginDetection.classify]), and what the GUI must actually do about it ([LoginDetection.resolve]).
 *
 * [LoginDetection.resolve] is what decides the sign-in card, and it is the only one that may: an access-token
 * expiry is renewed without the user **while a refresh token is left to spend**, and with none it is the end
 * of the identity, wearing the same words. So every "is this an authentication problem at all" case is
 * asserted against one of those two, never against a convenience answer nothing calls.
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
        // A message that mentions both quota and login should not nag about login — it's a billing issue.
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
        // Nothing can mint a token from a dead refresh token: this one really is a sign-in.
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
        // "expired" is not on its own evidence of anything: a plan, a trial or a link can expire.
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

    /** The reported 401, on a session whose vaulted refresh token is alive: renewed, so no card. */
    @Test
    fun `a renewable access-token expiry stays an expiry`() {
        assertEquals(AuthFailure.EXPIRED, LoginDetection.resolve(EXPIRY_401) { true })
        assertEquals(AuthFailure.EXPIRED, LoginDetection.resolve("OAuth token has expired") { true })
    }

    /**
     * **The regression.** This exact text raised the sign-in card before an expiry was a kind of its own, and
     * with nothing left to renew it must raise it again: no refresh token means nothing is going to mint an
     * access token, so the session is over until the user signs in. Getting this wrong is silent — the turn
     * fails, a row promises an automatic renewal, and no renewal is possible.
     */
    @Test
    fun `an expiry with nothing to renew is a missing identity`() {
        assertEquals(AuthFailure.NO_IDENTITY, LoginDetection.resolve(EXPIRY_401) { false })
        assertEquals(AuthFailure.NO_IDENTITY, LoginDetection.resolve("OAuth token has expired") { false })
    }

    /**
     * The safe is asked only when the answer can change the verdict. It is a round trip to the OS credential
     * store, on the EDT, for every failed turn — and an ordinary tool error is not an authentication failure
     * whatever the vault holds.
     */
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

    /** Every other verdict is the text's alone: renewability cannot turn one into an expiry, or out of one. */
    @Test
    fun `renewability changes nothing outside an access-token expiry`() {
        listOf(true, false).forEach { renewable ->
            assertEquals(AuthFailure.NONE, LoginDetection.resolve("Connection reset by peer") { renewable })
            assertEquals(AuthFailure.NONE, LoginDetection.resolve("Weekly quota exceeded") { renewable })
            assertEquals(AuthFailure.NO_IDENTITY, LoginDetection.resolve("401 Unauthorized") { renewable })
            assertEquals(AuthFailure.NO_IDENTITY, LoginDetection.resolve("Invalid API key provided") { renewable })
            // A dead refresh token is the end of the identity by the wording itself: whatever the vault says
            // it can renew, the thing renewal spends is gone.
            assertEquals(
                AuthFailure.NO_IDENTITY,
                LoginDetection.resolve("OAuth refresh token has expired. Please log in again.") { renewable },
            )
        }
    }

    /**
     * The invariant the split rests on: a text is an authentication failure exactly when it was one before,
     * whatever kind it is now sorted into and whichever way renewability answers. [LoginDetection.resolve] may
     * move a failure between kinds — that is its whole job — and may never move one out of the set, because
     * that is a session that never works again and says nothing about why.
     */
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
        /** The 401 a live session gets when its access token dies mid-turn, as the binary words it. */
        const val EXPIRY_401 =
            "Failed to authenticate. API Error: 401 OAuth access token has expired. Re-authenticate to continue."
    }
}
