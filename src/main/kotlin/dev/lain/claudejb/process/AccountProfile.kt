package dev.lain.claudejb.process

import com.intellij.openapi.diagnostic.thisLogger
import dev.lain.claudejb.settings.SecretStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.annotations.TestOnly
import java.io.File

object AccountProfile {

    private val log = thisLogger()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    data class Identity(val email: String?, val org: String?)

    @Volatile private var cached: Identity? = null

    @Volatile private var cachedAt = 0L

    @TestOnly
    @Volatile
    internal var homeOverride: File? = null

    private fun home(): File = homeOverride ?: File(System.getProperty("user.home").orEmpty())

    fun configFile(): File = File(home(), ".claude.json")

    fun invalidate() {
        cached = null
        cachedAt = 0
    }

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

    fun storedAccountJson(): String? = SecretStore.get(SecretStore.ACCOUNT_PROFILE)

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

    fun capture() {
        val account = accountObject() ?: return
        runCatching { SecretStore.set(SecretStore.ACCOUNT_PROFILE, account.toString()) }
        cached = identityOf(account)
        cachedAt = System.currentTimeMillis()
    }

    private const val TTL_MS = 60_000L
}
