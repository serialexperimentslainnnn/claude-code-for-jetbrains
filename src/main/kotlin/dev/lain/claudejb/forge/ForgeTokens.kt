package dev.lain.claudejb.forge

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import dev.lain.claudejb.settings.SecretStore

/**
 * One access token per HOST, in the IDE PasswordSafe (OS keychain / KWallet / DPAPI / encrypted file) and
 * nowhere else.
 *
 * **Per host, not per provider.** A GitHub Enterprise instance and github.com are two different credentials
 * that both say "GitHub", and so are a self-managed GitLab and gitlab.com. Keying on the provider would let
 * the token for a company's internal server be sent to a public API the moment somebody cloned a public
 * repository — the credential-scope mistake `SecretStore.envOverlay` already exists to prevent one layer
 * down. The host is the only key under which that cannot happen.
 *
 * **The store is reached through [SecretStore]'s read/write pair, never `PasswordSafe` directly.** That pair
 * is the test seam: it is inert in a test JVM that has not installed a store of its own, so a suite can never
 * read or write the developer's real keychain. A door onto the safe that skips it is a door a test leaks
 * through.
 *
 * The service name is the same subsystem the per-provider API keys use (`ClaudeCodeNative`), with the host in
 * the key, so this adds an entry beside them rather than a second scheme.
 *
 * **Nothing here logs.** A failure to read is indistinguishable from "no token" on purpose — both mean the
 * card does not draw — and a log line about a credential is the beginning of a log line containing one.
 */
object ForgeTokens {

    /** The stored token for [host], or null when there is none (the ordinary state) or the safe refused. */
    fun get(host: String): String? =
        runCatching { SecretStore.readCredential(key(host), attributes(host)) }.getOrNull()

    /** Stores [token] for [host]. A blank token clears the entry, so "delete" needs no second control. */
    fun set(host: String, token: String) {
        val trimmed = token.trim()
        runCatching { SecretStore.writeCredential(key(host), attributes(host), trimmed.ifEmpty { null }) }
    }

    /** Forgets the token for [host]. */
    fun clear(host: String) {
        runCatching { SecretStore.writeCredential(key(host), attributes(host), null) }
    }

    /**
     * The key form of a host: trimmed, lowercased, without a trailing dot.
     *
     * Hostnames are case-insensitive, so `GitHub.com` and `github.com` are one host and must not become two
     * entries — the failure mode being a token that was "definitely saved" and reads back as absent because
     * the remote spelled its host differently from the settings field. The trailing dot is the fully-qualified
     * spelling of the same name.
     */
    internal fun normalizeHost(host: String): String = host.trim().trimEnd('.').lowercase()

    private fun key(host: String) = "forgeToken:${normalizeHost(host)}"

    private fun attributes(host: String) =
        CredentialAttributes(generateServiceName("ClaudeCodeNative", key(host)))
}
