package dev.lain.claudejb.process

import com.intellij.openapi.diagnostic.thisLogger
import dev.lain.claudejb.settings.SecretStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.annotations.TestOnly
import java.io.File

/**
 * Who is signed in, read straight from the binary's own config instead of asked for.
 *
 * The account identity is not something the binary computes or fetches: it sits in `~/.claude.json` under
 * `oauthAccount` (`emailAddress`, `organizationName`, `accountUuid`), written at login. The plan name is
 * likewise carried inside the credentials blob the plugin already holds in its safe. So the dashboard does
 * not need the binary to tell it any of this — reading it directly means the account card is populated
 * whatever identity the session ends up running as, on every platform.
 *
 * The plan-limit windows are a different thing and they do NOT come from here: the binary fetches them from
 * the claude.ai usage endpoint and reports them through `get_usage`. It needs the account's OAuth scopes to
 * do it (`user:profile`), which is why the account object banked here is also fed to the process — see
 * [CredentialsVault.envOverlay]. Passing only the access token is what used to leave the meters dark.
 *
 * Cached in memory after the first read — this is a small file on a hot path (every dashboard push).
 */
object AccountProfile {

    private val log = thisLogger()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** `{email, org}` — either may be null. */
    data class Identity(val email: String?, val org: String?)

    @Volatile private var cached: Identity? = null

    @Volatile private var cachedAt = 0L

    /** Test seam, mirroring [CredentialsVault.homeOverride]. */
    @TestOnly
    @Volatile
    internal var homeOverride: File? = null

    private fun home(): File = homeOverride ?: File(System.getProperty("user.home").orEmpty())

    /** `~/.claude.json` — the binary's config, which also carries the signed-in account. */
    fun configFile(): File = File(home(), ".claude.json")

    /** Drops the cache, so the next read reflects a fresh sign-in. */
    fun invalidate() {
        cached = null
        cachedAt = 0
    }

    /**
     * The signed-in identity, or null when the file is absent/unreadable or holds no account.
     *
     * Re-read at most once per [TTL_MS]; a sign-in calls [invalidate] so a change is never waited out.
     */
    fun read(): Identity? {
        val now = System.currentTimeMillis()
        cached?.takeIf { now - cachedAt < TTL_MS }?.let { return it }
        val identity = fromSafe() ?: run {
            capture()
            fromSafe()
        } ?: return null
        cached = identity
        cachedAt = now
        return identity
    }

    /**
     * The WHOLE `oauthAccount` object as the binary wrote it, held in the safe.
     *
     * Kept entire rather than reduced to the two fields the dashboard shows: it is the binary's own object,
     * and a subset would be us deciding which of its fields it is allowed to have.
     */
    fun storedAccountJson(): String? = SecretStore.get(SecretStore.ACCOUNT_PROFILE)

    /** The safe is the source of record: it outlives `~/.claude.json` being replaced, moved or wiped. */
    private fun fromSafe(): Identity? = storedAccountJson()?.let { blob ->
        runCatching { identityOf(json.parseToJsonElement(blob).jsonObject) }.getOrNull()
    }

    private fun identityOf(account: kotlinx.serialization.json.JsonObject): Identity? = Identity(
        email = account["emailAddress"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
        org = account["organizationName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
    ).takeIf { it.email != null || it.org != null }

    private fun accountObject(): kotlinx.serialization.json.JsonObject? {
        val file = configFile()
        if (!file.isFile) return null
        return runCatching {
            json.parseToJsonElement(file.readText()).jsonObject["oauthAccount"]?.jsonObject
        }.getOrElse {
            log.warn("could not read the account profile from ~/.claude.json", it)
            null
        }
    }

    /**
     * Captures the account the binary just wrote and files it in the safe, whole. Called after a sign-in,
     * when that file is freshest — from then on the dashboard can name the account without it.
     */
    fun capture() {
        val account = accountObject() ?: return
        runCatching { SecretStore.set(SecretStore.ACCOUNT_PROFILE, account.toString()) }
        cached = identityOf(account)
        cachedAt = System.currentTimeMillis()
    }

    private const val TTL_MS = 60_000L
}
