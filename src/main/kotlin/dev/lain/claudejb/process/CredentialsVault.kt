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
 * **Expiry is handled by renewal, not by asking the user again** ([renew]). The access token lives hours —
 * measured at ~10 h on a fresh `auth login` — so with nothing but the token in the safe the identity died
 * overnight and the sign-in card was back after every reboot: the credential persisted perfectly and simply
 * expired. Only the binary can spend a refresh token, and that stays true here; the plugin does not hold an
 * OAuth client and does not talk to the token endpoint. What it does is give the binary **its own file back**
 * for the length of one token exchange ([renewOnDisk] — an environment-only renewal was tried first and never
 * worked, see the note there), lets it refresh and rewrite it, and harvests that the same way it harvests any
 * other login. The refresh token (weeks, and rotated at every renewal) becomes the thing that survives a
 * restart, and the plaintext file exists only for the moment between the binary writing it and [harvest]
 * taking it away.
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

    /** How long a failed renewal stops us trying again — the boot watcher polls every few seconds. */
    private const val RENEW_COOLDOWN_MS = 5 * 60 * 1000L

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
        // ONE read of the safe and ONE parse: reading the blob means a round trip to the OS credential store
        // (KWallet/Keychain/DPAPI), and this used to ask for it twice — once through usableToken(), once here.
        // It is not only cheaper, it removes a TOCTOU: with two reads the access token could come from one
        // blob and the refresh token/scopes beside it from a different one.
        val oauth = oauthNode() ?: return emptyMap()
        // Before accountNode(), which is a second trip to the safe: an unusable token means no overlay at all,
        // so there is nothing for the account fields to be attached to.
        val token = usableToken(oauth) ?: return emptyMap()
        return overlayFrom(token, oauth, accountNode())
    }

    /**
     * The blob → environment mapping itself, with no safe access in it: [envOverlay] decides *whether* there is
     * a credential to hand over, this decides *what* the child process is told about it.
     *
     * Split out to be pinnable. The map this returns is the entire authenticated identity of every session the
     * plugin runs, and it was previously reachable only through the PasswordSafe — so the field-by-field
     * mapping (the thing that broke `get_usage` when it was just the access token) had no direct test.
     * See `CredentialsVaultEnvTest`.
     */
    internal fun overlayFrom(
        token: String,
        oauth: kotlinx.serialization.json.JsonObject,
        account: kotlinx.serialization.json.JsonObject?,
    ): Map<String, String> {
        val env = mutableMapOf(SecretStore.OAUTH_TOKEN to token)
        oauth.string("refreshToken")?.let { env[ENV_REFRESH_TOKEN] = it }
        oauth.strings("scopes")?.takeIf { it.isNotEmpty() }?.let { env[ENV_SCOPES] = it.joinToString(" ") }
        oauth.string("subscriptionType")?.let { env[ENV_SUBSCRIPTION_TYPE] = it }
        oauth.string("rateLimitTier")?.let { env[ENV_RATE_LIMIT_TIER] = it }
        account?.let {
            it.string("accountUuid")?.let { v -> env[ENV_ACCOUNT_UUID] = v }
            it.string("organizationUuid")?.let { v -> env[ENV_ORGANIZATION_UUID] = v }
            it.string("emailAddress")?.let { v -> env[ENV_USER_EMAIL] = v }
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
     * Whether the vault holds an access token that can authenticate a session **right now**.
     *
     * An expired blob answers false — but that is no longer the end of the identity: see [canRenew], which
     * asks the second question ("can we mint a new one?"). Callers wanting "is there an identity at all"
     * must consider both, or they will show a sign-in card to a user whose credential only needed renewing.
     */
    fun hasUsableToken(): Boolean = usableToken() != null

    /**
     * Whether the vaulted blob can be turned back into a live access token without the user.
     *
     * Three conditions, all from the blob itself: a refresh token, the scopes it was issued with (planted
     * alongside it on disk for [renewOnDisk] and never restated — see [refreshEnv] for why the grant is not
     * repeated in the environment), and a
     * `refreshTokenExpiresAt` that is still in the future. A blob with no expiry recorded is given the
     * benefit of the doubt: the endpoint is the authority on that, and a wrong guess here costs one failed
     * renewal, while refusing costs a sign-in the user did not need.
     *
     * Also false during the cooldown a failed renewal sets, so a caller that polls every few seconds cannot
     * turn a transient network failure into a process spawn every few seconds.
     */
    fun canRenew(): Boolean {
        if (System.currentTimeMillis() < renewBlockedUntil) return false
        val oauth = oauthNode() ?: return false
        if (oauth.string("refreshToken") == null) return false
        if (oauth.strings("scopes").isNullOrEmpty()) return false
        val expiresAt = oauth["refreshTokenExpiresAt"]?.jsonPrimitive?.longOrNull ?: return true
        return expiresAt - System.currentTimeMillis() > EXPIRY_MARGIN_MS
    }

    /** An identity that exists but is not usable as it stands — exactly the case [renew] exists for. */
    fun needsRenewal(): Boolean = usableToken() == null && canRenew()

    /**
     * Brings the vaulted credential back to life. One implementation, [renewOnDisk] — the binary is given its
     * own file back for the length of one token exchange.
     *
     * **The environment route was tried for a release and never worked**, which is why it is not attempted
     * first: `CLAUDE_CODE_OAUTH_REFRESH_TOKEN` + `CLAUDE_CODE_OAUTH_SCOPES` is what shipped, and a login that
     * expired overnight kept asking the user to sign in again every morning regardless. The run that was taken
     * as proof of that path reached the token endpoint with a deliberately INVALID refresh token and got a
     * `400`, which says only that the branch exists — a malformed request and a rejected token are the same
     * status code. What has actually been observed working, on `claude` 2.1.226 with a real credential whose
     * `expiresAt` was in the past, is the file: the binary refreshed it and moved `expiresAt` nine hours
     * forward with no `CLAUDE_CODE_OAUTH_*` set at all.
     *
     * Kept as a named function rather than inlined at the call sites: the callers ask "renew this credential",
     * and how that is achieved is this file's business — the day the env route can be shown to work, it
     * changes here and nowhere else.
     *
     * BLOCKING — pooled thread only, and never while a [dev.lain.claudejb.session.LoginCoordinator] sign-in is
     * in flight (both write the same file; that guard belongs to the caller).
     *
     * @param baseEnv the RAW settings env. Deliberately not the launch env — handing the binary the expired
     *   access token we are trying to replace is at best noise and at worst the thing it authenticates with.
     */
    fun renew(binary: File, baseEnv: Map<String, String>): Boolean = renewOnDisk(binary, baseEnv)

    /**
     * The environment the refresh run gets: the settings env **minus the access token**.
     *
     * **This is what makes the on-disk refresh possible at all, and dropping it would silently defeat it.** The
     * binary resolves an env credential BEFORE its own store, so handing it `CLAUDE_CODE_OAUTH_TOKEN` — the
     * very token that expired or was revoked — means it authenticates with that and never looks at the file we
     * just planted for it. There is then nothing to refresh and nothing rewritten, and the failure looks like
     * "the binary refuses to refresh" rather than "we told it not to".
     *
     * **Stripped CASE-INSENSITIVELY**: environment names are case-insensitive on Windows, so a hand-written
     * `Claude_Code_Oauth_Token` in Settings would survive an exact-match removal and be exactly the credential
     * the refresh exists to replace.
     *
     * **Every `CLAUDE_CODE_OAUTH_*` goes, not just the access token**, so the planted file is the only thing
     * the binary can refresh from. The refresh token and the scopes are not re-added here — they are in the
     * file, and putting them in the environment as well is the route that shipped for a release and never
     * renewed anything. Leaving a hand-written one from Settings in place would make the outcome depend on
     * which source the binary happens to prefer, which is not a thing to discover from a bug report.
     */
    internal fun refreshEnv(baseEnv: Map<String, String>): Map<String, String> =
        baseEnv.filterKeys { !it.startsWith(OAUTH_ENV_PREFIX, ignoreCase = true) }

    /** The family of names the credential travels under, stripped as a whole by [refreshEnv]. */
    private const val OAUTH_ENV_PREFIX = "CLAUDE_CODE_OAUTH"

    /** Set by a failed [renew]; see [canRenew]. */
    @Volatile
    private var renewBlockedUntil = 0L

    /**
     * True for the length of [renewOnDisk]'s window — the only period in which `~/.claude/.credentials.json`
     * is on disk on purpose. [harvest] refuses while it is set; see the note there for why the lock is on the
     * operation rather than on its callers.
     */
    @Volatile
    private var renewingOnDisk = false

    /**
     * Renewal by giving the binary **its own file back** for the length of one call: plant, refresh, harvest,
     * delete. The fallback for a binary that will not refresh from the environment alone.
     *
     * **Why this exists when [renew] already does the same job through the env.** Measured against `claude`
     * 2.1.226 with a real credential whose `expiresAt` had been moved into the past: with
     * `.credentials.json` and `.claude.json` in place and **no** `CLAUDE_CODE_OAUTH_*` variables at all, the
     * binary refreshed the access token and rewrote the file by itself — `expiresAt` moved nine hours forward
     * on a run nobody typed anything into. That is the behaviour this reproduces, and it is the one path that
     * has actually been observed to work end to end.
     *
     * **What it costs, stated because the vault exists to prevent exactly this**: the credential is in
     * cleartext on disk for the length of one token exchange. Five things bound that, and none is optional:
     *
     *  1. the file is written `600` before anything is put in it — never created world-readable and then
     *     tightened, which is a window of its own;
     *  2. **the delete is in a `finally`**, so a throw, a timeout or a killed process cannot leave it behind;
     *  3. it is skipped entirely if a file is already there — that means a login is in flight or the binary is
     *     mid-write, and planting over it would destroy a credential we did not create;
     *  4. [renewingOnDisk] stops [harvest] — and therefore the boot watcher's 3-second poll — from taking the
     *     file away while the binary is reading it;
     *  5. the window is one `auth login` run with a 20 s ceiling, not the life of a session.
     *
     * The verdict is read from the FILE, never from the process: see [AuthCli.refreshUsingOwnFiles] for why the
     * exit code says nothing here.
     *
     * BLOCKING — pooled thread only, and never while a [dev.lain.claudejb.session.LoginCoordinator] sign-in is
     * in flight: both write this same file, and that guard belongs to the caller.
     */
    fun renewOnDisk(binary: File, baseEnv: Map<String, String>): Boolean {
        if (inertHere()) return false
        val blob = SecretStore.get(SecretStore.CREDENTIALS_JSON)?.takeIf { it.isNotBlank() } ?: return false
        val file = credentialsFile()
        if (file.exists()) {
            // Not ours to overwrite. Whatever put it there — a terminal login, a sign-in mid-flow — owns it,
            // and the right move is to take custody of THAT rather than to plant over it.
            log.info("a credentials file is already on disk; harvesting it instead of planting one")
            return harvest() && hasUsableToken()
        }
        renewingOnDisk = true
        val renewed = try {
            if (!plant(file, blob)) {
                false
            } else {
                // The env WITHOUT the access token — see [refreshEnv]: left in, the binary authenticates with
                // the dead token instead of refreshing from the file we just planted.
                AuthCli.refreshUsingOwnFiles(binary, refreshEnv(baseEnv))
                // The account object is rewritten by the same run, and it is the freshest it will ever be.
                AccountProfile.capture()
                harvestNow() && hasUsableToken()
            }
        } finally {
            wipe(file)
            renewingOnDisk = false
        }
        if (!renewed) log.warn("the on-disk credential refresh did not produce a usable token")
        renewBlockedUntil = if (renewed) 0L else System.currentTimeMillis() + RENEW_COOLDOWN_MS
        return renewed
    }

    /**
     * Writes [blob] to [file] with owner-only permissions, creating `~/.claude` if needed.
     *
     * The permissions are set on an EMPTY file and the content goes in afterwards, which is the opposite of the
     * obvious order and the only safe one: `writeText` then `setReadable(false, false)` publishes the
     * credential world-readable for however long the two calls take. On a filesystem or OS that refuses POSIX
     * permissions the write is abandoned rather than done insecurely — there is a working env-based path and a
     * sign-in card behind this, so failing closed costs a renewal, not the session.
     */
    private fun plant(file: File, blob: String): Boolean = runCatching {
        file.parentFile?.mkdirs()
        if (!file.createNewFile()) return false // lost a race with something else — do not touch it
        val ownerOnly = file.setReadable(false, false) && file.setWritable(false, false) &&
            file.setReadable(true, true) && file.setWritable(true, true)
        if (!ownerOnly) {
            log.warn("could not make the credentials file owner-only; refusing to write a credential to it")
            file.delete()
            return false
        }
        file.writeText(blob)
        true
    }.onFailure { log.warn("could not plant the credentials file for a refresh", it) }.getOrDefault(false)

    /** Overwrite-then-unlink, for the paths where [harvestNow] did not already do it. Never throws. */
    private fun wipe(file: File) {
        if (!file.isFile) return
        runCatching {
            file.writeText(" ".repeat(file.length().coerceAtMost(MAX_WIPE_BYTES).toInt()))
            file.delete()
        }.onFailure { log.warn("could not remove the planted credentials file", it) }
    }

    /** Ceiling on the overwrite in [wipe]: a credential is ~500 bytes, and a huge file here is not one. */
    private const val MAX_WIPE_BYTES = 64L * 1024

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

    private fun usableToken(): String? = oauthNode()?.let(::usableToken)

    private fun usableToken(oauth: kotlinx.serialization.json.JsonObject): String? {
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
        // THE LOCK LIVES HERE, not in the callers, and that placement is the point. `harvest()` is called from
        // `launch()`, from `stop()` AND from the boot watcher, which polls every 3 s while no session is up —
        // so during [renewOnDisk]'s window the file the binary is refreshing would be taken out from under it
        // by a timer nobody was thinking about. Guarding the three call sites means remembering to guard the
        // fourth; guarding the operation means the window is closed for every route that exists or is added.
        if (renewingOnDisk) {
            log.debug("not harvesting: a credential refresh is using the file right now")
            return false
        }
        return harvestNow()
    }

    /** [harvest] without the lock — for [renewOnDisk], which holds it and is the one caller that must proceed. */
    private fun harvestNow(): Boolean {
        val file = credentialsFile()
        if (!file.isFile) return false
        val text = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
        if (text == null) {
            log.warn("credentials file present but unreadable/empty — leaving it alone")
            return false
        }
        // VERIFIED, not fire-and-forget. `PasswordSafe.set` returns Unit and throws nothing when the OS
        // store rejects the write — on this machine the IDE logged
        // `secret_password_store_sync error code 36 — Can't find session …` as its own SEVERE, after our
        // call had returned. Harvesting is a MOVE: it deletes the file straight afterwards, so a write that
        // silently did nothing destroyed the user's only credential and the plugin asked them to sign in
        // again with no idea why. Reading it back is the only honest confirmation available.
        if (!SecretStore.setVerified(SecretStore.CREDENTIALS_JSON, text)) {
            log.warn("the password safe did not keep the credential — leaving the file where it is")
            dev.lain.claudejb.settings.SafeAlarm.storeFailed()
            return false
        }
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
