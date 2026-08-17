package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.process.AccountProfile
import dev.lain.claudejb.process.AuthCli
import dev.lain.claudejb.process.CredentialsVault
import dev.lain.claudejb.protocol.AccountInfo
import dev.lain.claudejb.session.ClaudeSession
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The dashboard's account card. One card of [JcefSessionData]'s payload; see there for the whole shape. */
internal object JcefAccountData {

    /**
     * `{ email, org, plan, provider, loggedIn }` — session-reported account fields, enriched by the
     * `auth status` probe ([AuthGate.status]), which also knows about a session that has no
     * account because it is NOT signed in. `loggedIn` drives the dashboard's Sign in / Log out button:
     * absent (null) when unknown, so the button doesn't claim a state nobody verified.
     *
     * Four sources answer for the identity, in this order: the session's own account event, the probe, the
     * stored reply below, and last the account [AccountProfile] banked in the safe at sign-in.
     */
    fun accountJson(session: ClaudeSession): JsonObject? = accountJson(
        session.account,
        session.auth.status,
        // The stored `auth status` reply — the binary's own words, filed in the safe by the last probe. The
        // probe is a process spawn and cannot run on every push, so without this the card had nothing to show
        // between probes and the Email / Organization rows sat empty.
        AuthCli.stored(),
        CredentialsVault.subscriptionType(),
        AccountProfile.read(),
    )

    /**
     * The card itself, out of the sources that answer for it, so the fallback order can be pinned without a
     * live session.
     *
     * [profile] is LAST for email and organization, and it is what fills them on the plugin's primary
     * authentication route: the credential reaches the binary as `CLAUDE_CODE_OAUTH_TOKEN`, and `auth status`
     * then answers with a reduced identity that carries neither. It is also the oldest of the four — it dates
     * from the sign-in — so anything the session or the binary says now wins over it.
     *
     * Neither [profile] nor [vaultedPlan] can make the card APPEAR: emptiness is decided by the session's own
     * account and the two `auth status` replies. A card conjured out of the safe alone would describe an
     * identity this session is not running as.
     */
    internal fun accountJson(
        account: AccountInfo,
        probe: AuthCli.AuthState?,
        stored: AuthCli.AuthState?,
        vaultedPlan: String?,
        profile: AccountProfile.Identity?,
    ): JsonObject? {
        val empty = account.email.isBlank() && account.organization.isBlank() &&
            account.subscriptionType.isBlank() && account.apiProvider.isBlank() && probe == null && stored == null
        if (empty) return null
        return buildJsonObject {
            put("email", firstPresent(account.email, probe?.email, stored?.email, profile?.email))
            put("org", firstPresent(account.organization, probe?.orgName, stored?.orgName, profile?.org))
            // Last resort, the vaulted blob: the plan is also carried inside the credential we hold, so the
            // row survives even a session that never managed to probe.
            put(
                "plan",
                firstPresent(
                    account.subscriptionType,
                    probe?.subscriptionType,
                    stored?.subscriptionType,
                    vaultedPlan,
                ),
            )
            // `apiProvider` ("firstParty") before `authMethod` ("claude.ai"): both describe the route, and the
            // former is the one the session's own account event uses, so the row can't change vocabulary
            // depending on which source answered.
            put(
                "provider",
                firstPresent(
                    account.apiProvider,
                    probe?.apiProvider,
                    probe?.authMethod,
                    stored?.apiProvider,
                    stored?.authMethod,
                ),
            )
            // The stored reply counts as verified: it IS a past `auth status`, and Log out clears the safe
            // (AUTH_STATUS included), so it cannot outlive the identity it describes.
            put("loggedIn", probe?.loggedIn ?: stored?.loggedIn)
        }
    }

    /**
     * The first candidate that carries something, or null. Blank counts as absent: the session's own account
     * object reports its unknown fields as `""`, and an empty string is a value the frontend would happily
     * render as a present-but-empty row.
     */
    internal fun firstPresent(vararg candidates: String?): String? =
        candidates.firstOrNull { !it.isNullOrBlank() }
}
