package dev.lain.claudejb.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import org.jetbrains.annotations.TestOnly

object SecretStore {

    const val OAUTH_TOKEN = "CLAUDE_CODE_OAUTH_TOKEN"

    const val API_KEY = "ANTHROPIC_API_KEY"

    const val CREDENTIALS_JSON = "CLAUDE_CREDENTIALS_JSON"

    const val ACCOUNT_PROFILE = "CLAUDE_ACCOUNT_PROFILE"

    const val AUTH_STATUS = "CLAUDE_AUTH_STATUS"

    const val ENV_VARS = "CLAUDE_ENV_VARS"

    const val SETTINGS_JSON = "CLAUDE_SETTINGS_JSON"

    const val SIGNED_OUT = "CLAUDE_SIGNED_OUT"

    private val EXCLUSIVE = listOf(OAUTH_TOKEN, CREDENTIALS_JSON)

    private val CREDENTIALS = EXCLUSIVE + ACCOUNT_PROFILE + AUTH_STATUS

    private val NAMES = CREDENTIALS + ENV_VARS + SETTINGS_JSON + SIGNED_OUT

    private val SCOPED_SETTINGS_PREFIX = "$SETTINGS_JSON@"

    private val ENV_NAMES = listOf(OAUTH_TOKEN)

    private fun attributes(name: String) =
        CredentialAttributes(generateServiceName("Claude Code", name))

    @TestOnly
    @Volatile
    internal var storeOverride: MutableMap<String, String>? = null

    internal fun inert(): Boolean {
        if (storeOverride != null) return false
        return ApplicationManager.getApplication()?.isUnitTestMode ?: true
    }

    internal fun readCredential(key: String, attributes: CredentialAttributes): String? {
        storeOverride?.let { return it[key]?.takeIf(String::isNotBlank) }
        if (inert()) return null
        return PasswordSafe.instance.getPassword(attributes)?.takeIf { it.isNotBlank() }
    }

    internal fun writeCredential(key: String, attributes: CredentialAttributes, value: String?) {
        storeOverride?.let { store ->
            if (value == null) store.remove(key) else store[key] = value
            return
        }
        if (inert()) return
        PasswordSafe.instance.set(attributes, value?.let { Credentials(key, it) })
    }

    fun get(name: String): String? = readCredential(name, attributes(name))

    fun set(name: String, value: String) {
        require(isKnown(name)) { "unknown secret: $name" }
        writeCredential(name, attributes(name), value)
        if (name in EXCLUSIVE) EXCLUSIVE.filter { it != name }.forEach { clear(it) }
    }

    private fun isKnown(name: String): Boolean =
        name in NAMES || (name.startsWith(SCOPED_SETTINGS_PREFIX) && name.length > SCOPED_SETTINGS_PREFIX.length)

    fun setVerified(name: String, value: String): Boolean = runCatching {
        set(name, value)
        get(name) == value
    }.getOrElse { false }

    fun clear(name: String) {
        writeCredential(name, attributes(name), null)
    }

    fun clearAll() = CREDENTIALS.forEach(::clear)

    fun envOverlay(explicitNames: Set<String>): Map<String, String> {
        if (API_KEY in explicitNames) return emptyMap()
        return ENV_NAMES.filter { it !in explicitNames }
            .mapNotNull { name -> get(name)?.let { name to it } }
            .toMap()
    }
}
