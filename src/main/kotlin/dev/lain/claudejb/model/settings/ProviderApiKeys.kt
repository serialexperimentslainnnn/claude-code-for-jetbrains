package dev.lain.claudejb.model.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName

internal object ProviderApiKeys {

    private fun keyName(provider: Provider) = "providerApiKey:${provider.id}"

    private fun credentials(provider: Provider) =
        CredentialAttributes(generateServiceName("ClaudeCodeNative", keyName(provider)))

    fun get(provider: Provider): String =
        runCatching { SecretStore.readCredential(keyName(provider), credentials(provider)) }.getOrNull().orEmpty()

    fun set(provider: Provider, key: String) {
        val trimmed = key.trim()
        runCatching { SecretStore.writeCredential(keyName(provider), credentials(provider), trimmed.ifEmpty { null }) }
    }
}
