package dev.lain.claudejb.process

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Takes custody of the API key that `claude auth login --console` mints for itself.
 *
 * The Console sign-in is the route an ORGANISATION wants: the OAuth consent it requests includes
 * `org:create_api_key`, so the binary creates a key for Claude Code on the user's behalf ("Creating API key
 * for Claude Code…") instead of anyone pasting one around. What it then does with that key is the problem
 * this class exists for — verified against 2.1.223's own code:
 *
 * ```js
 * await wr(n => ({ ...n, primaryApiKey: e, customApiKeyResponses: { …approved: [...] } }))
 * ```
 *
 * i.e. it writes the key **in clear text** into `~/.claude.json` (the macOS Keychain branch exists in the
 * binary but is switched off) — a long-lived billing credential in a file readable by anything running as the
 * user, shared with every terminal on the machine. That is precisely what [CredentialsVault] exists to stop
 * for the subscription login, so the Console login gets the same treatment: [harvest] reads the key, strips it
 * out of the file, and hands it back for the caller to file in the IDE's PasswordSafe.
 *
 * The cost is stated rather than hidden, exactly as it is for the subscription vault: the user's own terminal
 * CLI loses that key, because it was never the plugin's to leave lying there. Signing in again in a terminal
 * mints another one.
 */
object ConsoleApiKey {

    private val log = thisLogger()

    private const val PRIMARY_API_KEY = "primaryApiKey"

    /**
     * The Console-minted key, removed from `~/.claude.json` on the way out — or null when there is none, the
     * file is absent/unparseable, or we are inert (a test JVM pointed at a real home).
     *
     * The removal is an AMENDMENT: every other field of the CLI's config is preserved verbatim
     * ([ApiKeyApproval.readConfig]/[ApiKeyApproval.writeConfig]), because this is the user's shared config and
     * clobbering it would be a worse bug than the one being fixed. If the write fails the key is NOT returned:
     * better to leave the credential where the binary put it than to report it vaulted while a copy stays on
     * disk under a name nothing will ever clean up.
     */
    fun harvest(): String? {
        if (ApiKeyApproval.inert()) return null
        val root = ApiKeyApproval.readConfig() ?: return null
        val key = root[PRIMARY_API_KEY]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val stripped = buildJsonObject {
            root.forEach { (k, v) -> if (k != PRIMARY_API_KEY) put(k, v) }
        }
        if (!ApiKeyApproval.writeConfig(stripped)) {
            log.warn("could not strip $PRIMARY_API_KEY from ~/.claude.json — leaving the key where the binary put it")
            return null
        }
        return key
    }
}
