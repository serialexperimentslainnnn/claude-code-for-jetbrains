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

object CredentialsVault {

    private val log = thisLogger()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private const val EXPIRY_MARGIN_MS = 10 * 60 * 1000L

    private const val RENEW_COOLDOWN_MS = 5 * 60 * 1000L

    private const val ENV_REFRESH_TOKEN = "CLAUDE_CODE_OAUTH_REFRESH_TOKEN"
    private const val ENV_SCOPES = "CLAUDE_CODE_OAUTH_SCOPES"
    private const val ENV_SUBSCRIPTION_TYPE = "CLAUDE_CODE_SUBSCRIPTION_TYPE"
    private const val ENV_RATE_LIMIT_TIER = "CLAUDE_CODE_RATE_LIMIT_TIER"
    private const val ENV_ACCOUNT_UUID = "CLAUDE_CODE_ACCOUNT_UUID"
    private const val ENV_ORGANIZATION_UUID = "CLAUDE_CODE_ORGANIZATION_UUID"
    private const val ENV_USER_EMAIL = "CLAUDE_CODE_USER_EMAIL"

    @TestOnly
    @Volatile
    internal var homeOverride: File? = null

    fun credentialsFile(): File =
        File(homeOverride ?: File(System.getProperty("user.home").orEmpty()), ".claude/.credentials.json")

    private fun inertHere(): Boolean {
        if (homeOverride != null) return false
        return ApplicationManager.getApplication()?.isUnitTestMode ?: true
    }

    fun envOverlay(existing: Set<String>): Map<String, String> {
        if (SecretStore.OAUTH_TOKEN in existing || SecretStore.API_KEY in existing) return emptyMap()
        val oauth = oauthNode() ?: return emptyMap()
        val token = usableToken(oauth) ?: return emptyMap()
        return overlayFrom(token, oauth, accountNode())
    }

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

    private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun kotlinx.serialization.json.JsonObject.strings(name: String): List<String>? =
        (this[name] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }

    private fun accountNode() = AccountProfile.storedAccountJson()?.let { blob ->
        runCatching { json.parseToJsonElement(blob).jsonObject }.getOrNull()
    }

    fun hasUsableToken(): Boolean = usableToken() != null

    fun canRenew(): Boolean {
        if (System.currentTimeMillis() < renewBlockedUntil) return false
        val oauth = oauthNode() ?: return false
        if (oauth.string("refreshToken") == null) return false
        if (oauth.strings("scopes").isNullOrEmpty()) return false
        val expiresAt = oauth["refreshTokenExpiresAt"]?.jsonPrimitive?.longOrNull ?: return true
        return expiresAt - System.currentTimeMillis() > EXPIRY_MARGIN_MS
    }

    fun needsRenewal(): Boolean = usableToken() == null && canRenew()

    fun renew(binary: File, baseEnv: Map<String, String>): Boolean = renewOnDisk(binary, baseEnv)

    internal fun refreshEnv(baseEnv: Map<String, String>): Map<String, String> =
        baseEnv.filterKeys { !it.startsWith(OAUTH_ENV_PREFIX, ignoreCase = true) }

    private const val OAUTH_ENV_PREFIX = "CLAUDE_CODE_OAUTH"

    @Volatile
    private var renewBlockedUntil = 0L

    @Volatile
    private var renewingOnDisk = false

    fun renewOnDisk(binary: File, baseEnv: Map<String, String>): Boolean {
        if (inertHere()) return false
        val blob = SecretStore.get(SecretStore.CREDENTIALS_JSON)?.takeIf { it.isNotBlank() } ?: return false
        val file = credentialsFile()
        if (file.exists()) {
            log.info("a credentials file is already on disk; harvesting it instead of planting one")
            return harvest() && hasUsableToken()
        }
        renewingOnDisk = true
        val renewed = try {
            if (!plant(file, blob)) {
                false
            } else {
                AuthCli.refreshUsingOwnFiles(binary, refreshEnv(baseEnv))
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

    private fun plant(file: File, blob: String): Boolean = runCatching {
        file.parentFile?.mkdirs()
        if (!file.createNewFile()) return false
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

    private fun wipe(file: File) {
        if (!file.isFile) return
        runCatching {
            file.writeText(" ".repeat(file.length().coerceAtMost(MAX_WIPE_BYTES).toInt()))
            file.delete()
        }.onFailure { log.warn("could not remove the planted credentials file", it) }
    }

    private const val MAX_WIPE_BYTES = 64L * 1024

    fun subscriptionType(): String? = oauthNode()?.get("subscriptionType")?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() }

    private fun oauthNode() = SecretStore.get(SecretStore.CREDENTIALS_JSON)?.let { blob ->
        runCatching { json.parseToJsonElement(blob).jsonObject["claudeAiOauth"]?.jsonObject }.getOrNull()
    }

    private fun usableToken(): String? = oauthNode()?.let(::usableToken)

    private fun usableToken(oauth: kotlinx.serialization.json.JsonObject): String? {
        val token = oauth["accessToken"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val expiresAt = oauth["expiresAt"]?.jsonPrimitive?.longOrNull ?: return null
        return token.takeIf { expiresAt - System.currentTimeMillis() > EXPIRY_MARGIN_MS }
    }

    fun harvest(): Boolean {
        if (inertHere()) return false
        if (renewingOnDisk) {
            log.debug("not harvesting: a credential refresh is using the file right now")
            return false
        }
        return harvestNow()
    }

    private fun harvestNow(): Boolean {
        val file = credentialsFile()
        if (!file.isFile) return false
        val text = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
        if (text == null) {
            log.warn("credentials file present but unreadable/empty — leaving it alone")
            return false
        }
        if (!SecretStore.setVerified(SecretStore.CREDENTIALS_JSON, text)) {
            log.warn("the password safe did not keep the credential — leaving the file where it is")
            dev.lain.claudejb.settings.SafeAlarm.storeFailed()
            return false
        }
        runCatching {
            file.writeText(" ".repeat(text.length))
            file.delete()
        }.onFailure { log.warn("could not remove the credentials file after harvesting", it) }
        return true
    }

    fun clear() {
        if (inertHere()) return
        SecretStore.clear(SecretStore.CREDENTIALS_JSON)
        val file = credentialsFile()
        if (file.isFile) {
            runCatching { file.delete() }.onFailure { log.warn("could not delete the credentials file", it) }
        }
    }
}
