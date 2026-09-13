package dev.lain.claudejb.view.payload.panel

import dev.lain.claudejb.controller.process.auth.AccountProfile
import dev.lain.claudejb.controller.process.auth.AuthCli
import dev.lain.claudejb.controller.process.credentials.CredentialsVault
import dev.lain.claudejb.controller.session.ClaudeSession
import dev.lain.claudejb.model.protocol.models.AccountInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object JcefAccountData {

    fun accountJson(session: ClaudeSession): JsonObject? = accountJson(
        session.catalog.account,
        session.lifecycle.auth.status,
        AuthCli.stored(),
        CredentialsVault.subscriptionType(),
        AccountProfile.read(),
    )

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
            put(
                "plan",
                firstPresent(
                    account.subscriptionType,
                    probe?.subscriptionType,
                    stored?.subscriptionType,
                    vaultedPlan,
                ),
            )
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
            put("loggedIn", probe?.loggedIn ?: stored?.loggedIn)
        }
    }

    internal fun firstPresent(vararg candidates: String?): String? =
        candidates.firstOrNull { !it.isNullOrBlank() }
}
