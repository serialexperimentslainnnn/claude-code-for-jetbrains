package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.process.AccountProfile
import dev.lain.claudejb.process.AuthCli
import dev.lain.claudejb.protocol.AccountInfo
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The dashboard account card's fallback chain, on the pure JVM: which of the four sources answers for Email
 * and Organization, and what makes the card exist at all.
 *
 * The vaulted [AccountProfile] identity is the source under test. On the plugin's primary authentication
 * route the credential reaches the binary as `CLAUDE_CODE_OAUTH_TOKEN` and `auth status` answers without an
 * email, so the safe is the only place those two rows can come from — and it is also the one source that must
 * not be able to conjure a card for an identity the session is not running as.
 */
class JcefAccountDataTest {

    private val vaulted = AccountProfile.Identity(email = "vaulted@example.com", org = "Vaulted Org")

    @Test
    fun `the vaulted profile fills email and organization when the earlier sources carry neither`() {
        val card = JcefAccountData.accountJson(
            account = AccountInfo(),
            // The reduced identity `auth status` returns when asked with our own token: a route, no account.
            probe = AuthCli.AuthState(loggedIn = true, authMethod = "oauth_token", apiProvider = "firstParty"),
            stored = null,
            vaultedPlan = null,
            profile = vaulted,
        )
        requireNotNull(card)
        assertEquals("vaulted@example.com", card["email"]!!.jsonPrimitive.content)
        assertEquals("Vaulted Org", card["org"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the probe and the stored reply both win over the vaulted profile`() {
        val card = JcefAccountData.accountJson(
            account = AccountInfo(),
            probe = AuthCli.AuthState(email = "probe@example.com"),
            stored = AuthCli.AuthState(orgName = "Stored Org"),
            vaultedPlan = null,
            profile = vaulted,
        )
        requireNotNull(card)
        assertEquals("probe@example.com", card["email"]!!.jsonPrimitive.content)
        assertEquals("Stored Org", card["org"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a session with no account and no auth status has no card, whatever the safe holds`() {
        assertNull(
            JcefAccountData.accountJson(
                account = AccountInfo(),
                probe = null,
                stored = null,
                vaultedPlan = "max",
                profile = vaulted,
            ),
        )
    }
}
