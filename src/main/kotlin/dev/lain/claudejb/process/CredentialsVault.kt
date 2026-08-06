package dev.lain.claudejb.process

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import dev.lain.claudejb.settings.SecretStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.annotations.TestOnly
import java.io.File

/**
 * Keeps the subscription login OFF the disk, full stop.
 *
 * The full-consent OAuth flow (`claude auth login`) is the only one that grants the scopes Claude Code
 * exercises — file upload for pasted attachments among them — and it writes its credentials to
 * `~/.claude/.credentials.json`. That file is the CLI's own store: plaintext JSON on Linux, readable by
 * anything running as the user, and shared with every terminal session on the machine.
 *
 * So the plugin does not leave it there. [harvest] moves the file's contents into the IDE's PasswordSafe
 * (OS keychain / KWallet / DPAPI) and DELETES it — including a login the user made in their terminal,
 * which is deliberate: the plugin's identity is what it holds securely, and leaving a copy in plaintext
 * would defeat the whole exercise. Whoever wants the CLI signed in too can sign it in again.
 *
 * The credential is then fed back through the ENVIRONMENT, not the disk: [envOverlay] hands the binary
 * `CLAUDE_CODE_OAUTH_TOKEN`, which takes precedence over its own store (verified against 2.1.222 —
 * `auth status` flips `authMethod` from `claude.ai` to `oauth_token` when it is set). `/proc/<pid>/environ`
 * is 0400; the file was world-readable-by-the-user, so this is strictly narrower AND leaves nothing behind
 * once the process exits.
 *
 * **The file is never written back. Not once, not briefly, not at 0600.** Handing the credential back in
 * plaintext is the exact thing this class exists to stop, and a rule with an exception for the awkward case
 * is not a rule — the awkward case is where it would have mattered.
 *
 * **`~/.claude/.credentials.json` is the ONE file this plugin is allowed to delete, and this is the only
 * class allowed to delete it** — enforced by `NoFileDeletionContractTest`, which exists because a recursive
 * delete in the (now removed) session config dir followed symlinks into `~/.claude` and destroyed a user's
 * conversations, skills and session history. Nothing else on their disk is ours to remove.
 *
 * The cost is stated rather than hidden: only the binary can spend the refresh token, and it does that by
 * rewriting its own file. With no file it cannot, so when the access token expires the credential is simply
 * spent and the sign-in card comes back. A periodic sign-in is the price of never having a bearer token
 * sitting in a world-readable-by-the-user file.
 */
object CredentialsVault {

    private val log = thisLogger()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * How long before expiry a token stops counting as an identity. Starting a session on a token that dies
     * a minute later means a turn failing mid-flight; asking for a sign-in first is the honest version of
     * the same outcome.
     */
    private const val EXPIRY_MARGIN_MS = 10 * 60 * 1000L

    // The rest of the credential's env surface. Verified present in the shipped CLI's own env registry
    // (`sdk.mjs`/`bridge.mjs` name them, and sdk.mjs lists the OAuth ones in its subprocess passthrough).
    // `SecretStore.OAUTH_TOKEN` carries the access token itself.
    private const val ENV_REFRESH_TOKEN = "CLAUDE_CODE_OAUTH_REFRESH_TOKEN"
    private const val ENV_SCOPES = "CLAUDE_CODE_OAUTH_SCOPES"
    private const val ENV_SUBSCRIPTION_TYPE = "CLAUDE_CODE_SUBSCRIPTION_TYPE"
    private const val ENV_RATE_LIMIT_TIER = "CLAUDE_CODE_RATE_LIMIT_TIER"
    private const val ENV_ACCOUNT_UUID = "CLAUDE_CODE_ACCOUNT_UUID"
    private const val ENV_ORGANIZATION_UUID = "CLAUDE_CODE_ORGANIZATION_UUID"
    private const val ENV_USER_EMAIL = "CLAUDE_CODE_USER_EMAIL"

    /**
     * Overridable BY TESTS ONLY, and not a nicety: these operations MOVE a real credential, so a test run
     * against the developer's own home that died between harvest and restore would sign them out for real.
     * Production never sets it.
     */
    @TestOnly
    @Volatile
    internal var homeOverride: File? = null

    /** `~/.claude/.credentials.json` — the path the binary writes and reads. */
    fun credentialsFile(): File =
        File(homeOverride ?: File(System.getProperty("user.home").orEmpty()), ".claude/.credentials.json")

    /**
     * Hard refusal to touch a real home from a test JVM, and it is here because it already went wrong:
     * the integration tests start a real [dev.lain.claudejb.session.ClaudeSession], whose `launch()` calls
     * [harvest] — which read the developer's own credentials, filed them in a throwaway test PasswordSafe
     * and deleted them. It was invisible while `launch()` still wrote the file back afterwards, and
     * destroyed a live login the moment it stopped doing so.
     *
     * So in unit-test mode the vault does nothing unless a test has explicitly pointed [homeOverride] at a
     * directory of its own. `getApplication()` is null in a pure-JVM test, which is also not a place to be
     * moving credentials around.
     */
    private fun inertHere(): Boolean {
        if (homeOverride != null) return false
        return ApplicationManager.getApplication()?.isUnitTestMode ?: true
    }

    /**
     * The launch environment's share of the credential: **the whole vaulted blob, field by field**, so the
     * binary authenticates with nothing on disk AND with the identity it actually wrote at login.
     *
     * THE BUG THIS FIXES, because it cost a user their session history before it was understood: only
     * `accessToken` used to be handed over. A bare access token leaves the binary without the OAuth
     * **scopes**, and the SDK is explicit about the consequence — `SDKControlGetUsageResponse` documents
     * `rate_limits_available` as *"False when plan rate limits do not apply (API key, Bedrock, Vertex, or
     * **missing profile scope**)"*. The stored blob grants `user:profile`; the env did not say so, so
     * `get_usage` answered `rate_limits: null` and every session meter went dark. That was misdiagnosed as
     * "the binary only reports this from its own config directory", which produced a relocated
     * `CLAUDE_CONFIG_DIR` full of symlinks into `~/.claude` and a recursive delete that emptied it. The
     * directory was never needed: the binary reads all of this from the environment.
     *
     * Mapping, from the file the CLI writes (`claudeAiOauth`) to the names the binary reads:
     *
     * ```
     * accessToken      -> CLAUDE_CODE_OAUTH_TOKEN
     * refreshToken     -> CLAUDE_CODE_OAUTH_REFRESH_TOKEN
     * scopes[]         -> CLAUDE_CODE_OAUTH_SCOPES        (space-separated, the OAuth `scope` encoding)
     * subscriptionType -> CLAUDE_CODE_SUBSCRIPTION_TYPE
     * rateLimitTier    -> CLAUDE_CODE_RATE_LIMIT_TIER
     * ```
     *
     * plus the account the same login wrote to `~/.claude.json`, held whole in the safe by [AccountProfile]:
     * `accountUuid` → `CLAUDE_CODE_ACCOUNT_UUID`, `organizationUuid` → `CLAUDE_CODE_ORGANIZATION_UUID`,
     * `emailAddress` → `CLAUDE_CODE_USER_EMAIL`.
     *
     * Absent fields are simply omitted — never blanked. An empty env var is a value, and a blank scope list
     * or subscription would be us telling the binary something false about the account.
     *
     * `CLAUDE_CODE_SDK_HAS_OAUTH_REFRESH` is deliberately NOT set: it announces that the HOST will refresh
     * the token, and this host cannot (only the binary can spend a refresh token). Claiming it would leave
     * an expiry with nobody handling it.
     *
     * Empty when the safe holds nothing, when the blob does not parse, when the token is at or within
     * [EXPIRY_MARGIN_MS] of expiry, or when [existing] already names a credential: an API key or a token
     * written by hand in Settings outranks anything we harvested.
     */
    fun envOverlay(existing: Set<String>): Map<String, String> {
        if (SecretStore.OAUTH_TOKEN in existing || SecretStore.API_KEY in existing) return emptyMap()
        val token = usableToken() ?: return emptyMap()
        val oauth = oauthNode() ?: return emptyMap()
        val env = mutableMapOf(SecretStore.OAUTH_TOKEN to token)
        oauth.string("refreshToken")?.let { env[ENV_REFRESH_TOKEN] = it }
        oauth.strings("scopes")?.takeIf { it.isNotEmpty() }?.let { env[ENV_SCOPES] = it.joinToString(" ") }
        oauth.string("subscriptionType")?.let { env[ENV_SUBSCRIPTION_TYPE] = it }
        oauth.string("rateLimitTier")?.let { env[ENV_RATE_LIMIT_TIER] = it }
        accountNode()?.let { account ->
            account.string("accountUuid")?.let { env[ENV_ACCOUNT_UUID] = it }
            account.string("organizationUuid")?.let { env[ENV_ORGANIZATION_UUID] = it }
            account.string("emailAddress")?.let { env[ENV_USER_EMAIL] = it }
        }
        return env
    }

    /** A non-blank string field, or null — so an absent field is omitted rather than sent as `""`. */
    private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    /** A string-array field (`scopes`), or null. Non-string entries are dropped rather than stringified. */
    private fun kotlinx.serialization.json.JsonObject.strings(name: String): List<String>? =
        (this[name] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }

    /** The `oauthAccount` object [AccountProfile] banked at sign-in, or null. */
    private fun accountNode() = AccountProfile.storedAccountJson()?.let { blob ->
        runCatching { json.parseToJsonElement(blob).jsonObject }.getOrNull()
    }

    /**
     * Whether the vault holds a subscription credential that can still authenticate a session.
     *
     * An EXPIRED blob deliberately answers false: it cannot be refreshed without writing the file back, so
     * it is not an identity any more. Callers treat that as signed-out and show the card, which beats
     * launching a session that will fail its first turn.
     */
    fun hasUsableToken(): Boolean = usableToken() != null

    /**
     * The plan name recorded in the vaulted blob (`max`, `pro`, …), or null.
     *
     * Needed because authenticating through `CLAUDE_CODE_OAUTH_TOKEN` gives the binary a REDUCED identity:
     * `auth status` then answers with `authMethod`/`apiProvider` and nothing else — no email, no plan —
     * where the same account read from its own file answers with all of it. Verified against 2.1.222. The
     * blob we hold carries the plan, so the dashboard need not lose that much.
     */
    fun subscriptionType(): String? = oauthNode()?.get("subscriptionType")?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() }

    /**
     * The stored access token, or null when it is absent, unparseable or too close to expiry to be worth
     * using. Parsed leniently: the blob is the binary's private format and may grow fields.
     */
    private fun oauthNode() = SecretStore.get(SecretStore.CREDENTIALS_JSON)?.let { blob ->
        runCatching { json.parseToJsonElement(blob).jsonObject["claudeAiOauth"]?.jsonObject }.getOrNull()
    }

    private fun usableToken(): String? {
        val oauth = oauthNode() ?: return null
        val token = oauth["accessToken"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val expiresAt = oauth["expiresAt"]?.jsonPrimitive?.longOrNull ?: return null
        return token.takeIf { expiresAt - System.currentTimeMillis() > EXPIRY_MARGIN_MS }
    }

    /**
     * Moves the credentials file into the safe and deletes it. No-op when the file is absent (nothing to
     * harvest) or blank (a half-written file is not a credential worth keeping).
     *
     * @return true when something was taken into the safe.
     */
    fun harvest(): Boolean {
        if (inertHere()) return false
        val file = credentialsFile()
        if (!file.isFile) return false
        val text = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
        if (text == null) {
            log.warn("credentials file present but unreadable/empty — leaving it alone")
            return false
        }
        SecretStore.set(SecretStore.CREDENTIALS_JSON, text)
        // Overwrite before unlinking: on a journalling filesystem the blocks may survive a bare delete,
        // and this content is a bearer credential.
        runCatching {
            file.writeText(" ".repeat(text.length))
            file.delete()
        }.onFailure { log.warn("could not remove the credentials file after harvesting", it) }
        return true
    }

    /** Wipes both halves: the safe entry and any file on disk. Used by Log out. */
    fun clear() {
        if (inertHere()) return
        SecretStore.clear(SecretStore.CREDENTIALS_JSON)
        val file = credentialsFile()
        if (file.isFile) {
            runCatching { file.delete() }.onFailure { log.warn("could not delete the credentials file", it) }
        }
    }
}
