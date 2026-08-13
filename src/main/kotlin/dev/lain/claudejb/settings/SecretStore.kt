package dev.lain.claudejb.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import org.jetbrains.annotations.TestOnly

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

    /**
     * The signed-in account (email, organization) — NOT a credential and NOT an auth mode, which is exactly
     * why it is kept out of [EXCLUSIVE]: storing it must never evict the credential beside it. Held here
     * rather than re-read from `~/.claude.json` each time so the dashboard can name the account even once
     * that file is gone.
     */
    const val ACCOUNT_PROFILE = "CLAUDE_ACCOUNT_PROFILE"

    /**
     * The last successful `claude auth status` reply, VERBATIM — the binary's own statement of who is signed
     * in (`loggedIn`, `authMethod`, `apiProvider`, `email`, `orgId`, `orgName`, `subscriptionType`).
     *
     * Kept here, and kept whole, for two reasons. It is the authoritative source for the dashboard's account
     * card: the probe is a process spawn, so it cannot run on every push, and without a stored copy the card
     * had nothing to show between probes. And it is identity, not credential — so like [ACCOUNT_PROFILE] it
     * stays out of [EXCLUSIVE] (storing it must never evict the credential beside it) and out of the child
     * environment.
     */
    const val AUTH_STATUS = "CLAUDE_AUTH_STATUS"

    /**
     * The user's own environment variables for the child process, as the `KEY=value` block they typed.
     *
     * **They are secrets by construction**: an env block is where people put an API key, a proxy with
     * credentials in the URL, or a token for a private registry. They used to sit in plaintext in
     * `.idea/claude-code.xml` — a file that gets committed — and the settings UI warned about exactly that
     * instead of fixing it. Held here, so they land in the OS keychain like every other secret the plugin
     * keeps, and so the settings file that replaced that XML never contains them.
     *
     * Not in [EXCLUSIVE] (it is not an auth mode and must never evict a credential) and not in [ENV_NAMES]
     * (the block is parsed by `ClaudeSettings.parseEnv`, which decides how it reaches the child).
     */
    const val ENV_VARS = "CLAUDE_ENV_VARS"

    /**
     * The plugin's whole configuration, as the JSON document [dev.lain.claudejb.settings.SettingsStore]
     * builds.
     *
     * **Why the settings live in the safe and not in a file.** They carry the user's environment block, and
     * an env block is where an API key, a credentialed proxy URL or a private-registry token ends up. That
     * was the reason they left `.idea/claude-code.xml` in the first place — a plaintext file people commit —
     * and writing them to `~/.claude/ide/claude-code-native/settings.json` instead only moved the plaintext
     * somewhere else. Here the whole document lands in the OS keychain (Keychain / KWallet / DPAPI /
     * encrypted file, whatever the IDE is configured with), like every other secret the plugin holds, and
     * there is no settings file on disk at all.
     *
     * It is one entry rather than a field-per-entry because the settings are read and written as a whole,
     * and a partial save is a configuration nobody chose.
     */
    const val SETTINGS_JSON = "CLAUDE_SETTINGS_JSON"

    /** Auth modes: mutually exclusive by construction — setting one clears the others. */
    private val EXCLUSIVE = listOf(OAUTH_TOKEN, CREDENTIALS_JSON)

    private val NAMES = EXCLUSIVE + ACCOUNT_PROFILE + AUTH_STATUS + ENV_VARS + SETTINGS_JSON

    /** The subset that is injected into the child environment — [CREDENTIALS_JSON] is file-shaped, not env. */
    private val ENV_NAMES = listOf(OAUTH_TOKEN)

    private fun attributes(name: String) =
        CredentialAttributes(generateServiceName("Claude Code", name))

    // --- The test seam -------------------------------------------------------------------------------
    //
    // WHAT THIS IS FOR, precisely, because the obvious guess is wrong: it is NOT protecting the developer's
    // OS keychain. A test JVM already cannot reach that, and not by our doing — the platform registers
    // `testServiceImplementation="TestPasswordSafeImpl"` for this service and `computeProvider` swaps in an
    // `InMemoryCredentialStore` whenever `isUnitTestMode`, before any native store is constructed. Measured
    // on the pinned IDE (253.28294.334): inside `BasePlatformTestCase`, `PasswordSafe.instance` is
    // `TestPasswordSafeImpl` over `InMemoryCredentialStore`, and the machine's real `CLAUDE_SETTINGS_JSON`
    // and `CLAUDE_CREDENTIALS_JSON` read back as null.
    //
    // The bug is one level in from that. `InMemoryCredentialStore` is a single APPLICATION service, the
    // platform test framework reuses ONE Application across every test class, and the `test` task sets
    // neither `maxParallelForks` nor `forkEvery` — so the whole suite shares one store. Measured, again:
    // `ClaudeSettingsConfigurableHeadlessTest` leaves its fixture document (`model = opus-pinned-by-the-user`,
    // `permissionMode = acceptEdits`) behind, and the next class in the run reads it. That is a test seeing a
    // value it never wrote, which is how `SettingsStoreHeadlessTest` was seen asserting on somebody else's
    // model — and a leaked `permissionMode` is not only a flake, it is a test running under a permission mode
    // nobody chose.
    //
    // So a test that needs the store to REMEMBER anything installs a store of its own, exactly the way
    // `CredentialsVault.homeOverride` makes a test name its own home. And the default when it has not is
    // INERT — no reads, no writes, nothing shared — rather than "fall back to the one everybody shares",
    // because falling back is the bug. Forgetting is then loud (a save is refused, a migration reports it did
    // nothing) instead of silently correlating two tests, and it fails closed: if the platform ever stopped
    // substituting the in-memory store, a test JVM would still not be the thing that discovers it.

    /**
     * The store a test has installed for itself, or null. Production never sets it, and must not: outside a
     * test JVM this is ignored entirely (see [inert]).
     */
    @TestOnly
    @Volatile
    internal var storeOverride: MutableMap<String, String>? = null

    /**
     * True when there is no store to talk to: a test JVM in which nothing has installed a [storeOverride].
     *
     * Same shape and same predicate as `CredentialsVault.inertHere()` — a named override wins, otherwise a
     * unit-test JVM (and a JVM with no Application at all, which is not a place to be reading credentials)
     * is refused. In production `isUnitTestMode` is false and an Application always exists, so this is
     * constantly false and the safe is used exactly as before.
     */
    internal fun inert(): Boolean {
        if (storeOverride != null) return false
        return ApplicationManager.getApplication()?.isUnitTestMode ?: true
    }

    /**
     * Reads one entry, from the installed test store, or the PasswordSafe, or nowhere.
     *
     * [key] identifies the entry inside a test store; [attributes] locates it in the real safe. They are two
     * spellings of one address, kept apart because the provider-key slots
     * ([ClaudeSettings.getProviderApiKey]) live under a different service name and must ride the same seam —
     * one door onto the safe, not two.
     */
    internal fun readCredential(key: String, attributes: CredentialAttributes): String? {
        storeOverride?.let { return it[key]?.takeIf(String::isNotBlank) }
        if (inert()) return null
        return PasswordSafe.instance.getPassword(attributes)?.takeIf { it.isNotBlank() }
    }

    /** Writes (or, on a null [value], removes) one entry. A no-op when the store is [inert]. */
    internal fun writeCredential(key: String, attributes: CredentialAttributes, value: String?) {
        storeOverride?.let { store ->
            if (value == null) store.remove(key) else store[key] = value
            return
        }
        if (inert()) return
        PasswordSafe.instance.set(attributes, value?.let { Credentials(key, it) })
    }

    fun get(name: String): String? = readCredential(name, attributes(name))

    /**
     * Stores [value] under [name] and CLEARS every sibling entry — the auth modes are exclusive, and a
     * leftover credential from a previous mode silently winning over the one the user just set is exactly
     * the kind of ghost this store exists to avoid.
     */
    fun set(name: String, value: String) {
        require(name in NAMES) { "unknown secret: $name" }
        writeCredential(name, attributes(name), value)
        // Only an auth mode evicts the other auth modes. The account profile sits alongside whichever one
        // is in use — clearing the credential every time we learned the user's email would be absurd.
        if (name in EXCLUSIVE) EXCLUSIVE.filter { it != name }.forEach { clear(it) }
    }

    /**
     * Stores [value] and READS IT BACK. Returns false when the safe did not actually keep it.
     *
     * **The read-back is the whole point, and it is not paranoia.** `PasswordSafe.set` returns `Unit` and
     * throws nothing when the OS store rejects the write: on this very machine the IDE logged
     * `secret_password_store_sync error code 36 — Can't find session /org/freedesktop/secrets/session/928`
     * (an expired Secret Service session) as a SEVERE of its own, *after* our call had returned normally.
     * A caller that then deleted the file it had just "migrated" — which is what both this store and
     * [dev.lain.claudejb.process.CredentialsVault] do — destroyed the only copy in existence. That is how a
     * configuration and a login disappeared on a reinstall, with every line of our code behaving as designed.
     *
     * So: nothing that deletes an original may call [set]. It must call this, and believe the answer.
     *
     * An [inert] store answers false here for free (the write goes nowhere, so the read-back finds nothing),
     * which is the behaviour a test JVM should have: refuse, loudly, rather than report a success nothing
     * kept.
     */
    fun setVerified(name: String, value: String): Boolean = runCatching {
        set(name, value)
        get(name) == value
    }.getOrElse { false }

    fun clear(name: String) {
        writeCredential(name, attributes(name), null)
    }

    fun clearAll() = NAMES.forEach(::clear)

    /**
     * What the launch env should gain from the safe: every stored credential whose name the explicit env
     * does NOT already define. The carve-out is the contract — a value the user wrote by hand in Settings
     * (or exported in their shell) keeps winning over the card-entered one.
     *
     * **An explicit [API_KEY] suppresses the whole overlay, and that is a credential-scope rule, not a
     * preference.** The env carries [API_KEY] in exactly two situations, and both of them say the session is
     * NOT running as the vaulted subscription identity: the user wrote a key by hand, or a third-party
     * provider is selected — in which case `Provider.launchEnv` has also set `ANTHROPIC_BASE_URL` to that
     * provider's endpoint. Adding [OAUTH_TOKEN] on top would put an Anthropic subscription token in the
     * environment of a process pointed at `api.deepseek.com`, with only the binary's own precedence rules
     * standing between it and the wire. [dev.lain.claudejb.process.CredentialsVault.envOverlay] already
     * refuses on exactly this condition; the two overlays feed the same env and must not disagree about it.
     */
    fun envOverlay(explicitNames: Set<String>): Map<String, String> {
        if (API_KEY in explicitNames) return emptyMap()
        return ENV_NAMES.filter { it !in explicitNames }
            .mapNotNull { name -> get(name)?.let { name to it } }
            .toMap()
    }
}
