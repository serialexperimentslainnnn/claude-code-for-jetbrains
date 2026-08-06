package dev.lain.claudejb.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * The plugin's credentials, in the IDE's PasswordSafe (OS keychain / KWallet / DPAPI / encrypted file —
 * whatever the user configured) and NOWHERE else.
 *
 * Why not `ClaudeSettings.envVars`, which can technically hold the same names: `claude-code.xml` is a
 * PROJECT-level file, plain XML, and committable — an API key there is one careless `git add` from being
 * published. The safe is application-level and encrypted, so a credential entered through the sign-in card
 * can never reach the repository.
 *
 * Two entries, mutually exclusive by construction ([set] clears the sibling): a session authenticates with
 * a subscription token OR an API key, and keeping both invites the confusion of not knowing which one the
 * binary actually used. Values are injected into the child process ENVIRONMENT only
 * (ClaudeSession.effectiveLaunchEnv) — never argv, never logs, never the transcript.
 */
object SecretStore {

    /** Env-var names the store manages. The name IS the key: what the binary reads is what we store under. */
    const val OAUTH_TOKEN = "CLAUDE_CODE_OAUTH_TOKEN"

    /**
     * The env-var name for an API key — a NAME only. The key itself is NOT kept here: an Anthropic API key
     * is stored exactly like every other provider's, through
     * [ClaudeSettings.setProviderApiKey] under `providerApiKey:anthropic`, so the sign-in card and the
     * provider field in Settings are two doors onto one credential instead of two credentials that quietly
     * disagree about which one the binary used.
     */
    const val API_KEY = "ANTHROPIC_API_KEY"

    /**
     * NOT an env var: the full content of the binary's `.credentials.json`, held here AT REST. The file
     * itself exists only while a session runs — [dev.lain.claudejb.process.CredentialsVault] materializes
     * it at launch and harvests+deletes it at teardown, so the subscription login's disk footprint is zero
     * whenever the plugin is idle.
     */
    const val CREDENTIALS_JSON = "CLAUDE_CREDENTIALS_JSON"

    private val NAMES = listOf(OAUTH_TOKEN, CREDENTIALS_JSON)

    /** The subset that is injected into the child environment — [CREDENTIALS_JSON] is file-shaped, not env. */
    private val ENV_NAMES = listOf(OAUTH_TOKEN)

    private fun attributes(name: String) =
        CredentialAttributes(generateServiceName("Claude Code", name))

    fun get(name: String): String? =
        PasswordSafe.instance.getPassword(attributes(name))?.takeIf { it.isNotBlank() }

    /**
     * Stores [value] under [name] and CLEARS every sibling entry — the auth modes are exclusive, and a
     * leftover credential from a previous mode silently winning over the one the user just set is exactly
     * the kind of ghost this store exists to avoid.
     */
    fun set(name: String, value: String) {
        require(name in NAMES) { "unknown secret: $name" }
        PasswordSafe.instance.set(attributes(name), Credentials(name, value))
        NAMES.filter { it != name }.forEach { clear(it) }
    }

    fun clear(name: String) {
        PasswordSafe.instance.set(attributes(name), null)
    }

    fun clearAll() = NAMES.forEach(::clear)

    /**
     * What the launch env should gain from the safe: every stored credential whose name the explicit env
     * does NOT already define. The carve-out is the contract — a value the user wrote by hand in Settings
     * (or exported in their shell) keeps winning over the card-entered one.
     */
    fun envOverlay(explicitNames: Set<String>): Map<String, String> =
        ENV_NAMES.filter { it !in explicitNames }
            .mapNotNull { name -> get(name)?.let { name to it } }
            .toMap()
}
