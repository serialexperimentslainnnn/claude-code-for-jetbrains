package dev.lain.claudejb.ui.jcef

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
     */
    fun accountJson(session: ClaudeSession): JsonObject? {
        val acct = session.account
        val probe = session.auth.status
        // The stored `auth status` reply — the binary's own words, filed in the safe by the last probe. The
        // probe is a process spawn and cannot run on every push, so without this the card had nothing to show
        // between probes and the Email / Organization rows sat empty.
        val stored = dev.lain.claudejb.process.AuthCli.stored()
        val empty = acct.email.isBlank() && acct.organization.isBlank() &&
            acct.subscriptionType.isBlank() && acct.apiProvider.isBlank() && probe == null && stored == null
        if (empty) return null
        return buildJsonObject {
            put("email", firstPresent(acct.email, probe?.email, stored?.email))
            put("org", firstPresent(acct.organization, probe?.orgName, stored?.orgName))
            // Last resort, the vaulted blob: the plan is also carried inside the credential we hold, so the
            // row survives even a session that never managed to probe.
            put(
                "plan",
                firstPresent(
                    acct.subscriptionType,
                    probe?.subscriptionType,
                    stored?.subscriptionType,
                    dev.lain.claudejb.process.CredentialsVault.subscriptionType(),
                ),
            )
            // `apiProvider` ("firstParty") before `authMethod` ("claude.ai"): both describe the route, and the
            // former is the one the session's own account event uses, so the row can't change vocabulary
            // depending on which source answered.
            put(
                "provider",
                firstPresent(
                    acct.apiProvider,
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
