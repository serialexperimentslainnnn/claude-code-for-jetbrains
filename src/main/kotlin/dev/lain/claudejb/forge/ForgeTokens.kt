package dev.lain.claudejb.forge

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import dev.lain.claudejb.settings.SecretStore

object ForgeTokens {

    fun get(host: String): String? =
        runCatching { SecretStore.readCredential(key(host), attributes(host)) }.getOrNull()

    fun set(host: String, token: String) {
        val trimmed = token.trim()
        runCatching { SecretStore.writeCredential(key(host), attributes(host), trimmed.ifEmpty { null }) }
    }

    fun clear(host: String) {
        runCatching { SecretStore.writeCredential(key(host), attributes(host), null) }
    }

    internal fun normalizeHost(host: String): String = host.trim().trimEnd('.').lowercase()

    private fun key(host: String) = "forgeToken:${normalizeHost(host)}"

    private fun attributes(host: String) =
        CredentialAttributes(generateServiceName("ClaudeCodeNative", key(host)))
}
