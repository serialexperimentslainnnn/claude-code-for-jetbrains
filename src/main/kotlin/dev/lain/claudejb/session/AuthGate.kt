package dev.lain.claudejb.session

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import dev.lain.claudejb.process.AccountProfile
import dev.lain.claudejb.process.AuthCli
import dev.lain.claudejb.process.ClaudeBinaryLocator
import dev.lain.claudejb.process.CredentialsVault
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.SecretStore
import dev.lain.claudejb.settings.resolveEnv
import java.io.File

/**
 * Who this session runs as: whether we hold an identity at all, whose it is, and keeping it alive.
 *
 * Separate from [ClaudeSession] because none of it is a turn — it is answered before a process exists and
 * re-answered while none is running. The session asks three questions ([hasCredential], [renew], [probe])
 * and is told the answer; the harvesting, the throttles and the order in which the binary is interrogated
 * live here.
 *
 * Everything here except [hasCredential] blocks: it spawns the binary. [hasCredential] is the one the EDT
 * is allowed to ask, which is why it deliberately neither harvests nor renews.
 */
class AuthGate(
    private val project: Project,
    /** A sign-in is mid-flight: it owns the credentials file, so nothing here may touch it. */
    private val signInInProgress: () -> Boolean,
    /** The env a session would launch with — the identity the probe must describe. */
    private val launchEnv: () -> Map<String, String>,
    /** The probe finished: true when the binary authenticated as somebody. Called off the EDT. */
    private val onProbed: (Boolean) -> Unit,
) {

    /** Last `auth status` probe result — feeds the dashboard's account card (email, plan, Sign in/Log out). */
    @Volatile
    var status: AuthCli.AuthState? = null
        private set

    @Volatile
    private var startupHarvestDone = false

    @Volatile private var binaryOwnLogin = false

    @Volatile private var ownLoginCheckedAt = 0L

    /**
     * ONCE per session, on the first boot check: if the machine already has a plaintext
     * `~/.claude/.credentials.json` — a login the user made in their terminal, or an orphan from a hard IDE
     * kill — take it into the safe and delete it. That login then counts as ours and the tab starts signed
     * in instead of asking again.
     *
     * Once, and only here. Doing it on every poll deleted the file every few seconds, and `auth login`
     * finishes by writing exactly that file: the browser leg lost its credential the instant it earned it,
     * and the code-paste fallback became the only route that ever completed. A sign-in in flight writes its
     * own credential into the safe when it succeeds ([LoginCoordinator]) — the vault does not need to go
     * looking for it.
     */
    fun absorbExistingLoginOnce() {
        if (startupHarvestDone) return
        startupHarvestDone = true
        // ORDER IS THE WHOLE POINT, and it is the same order the card's sign-in follows
        // ([LoginCoordinator.completeSignIn]): ask WHO first, take the credential second. Reversed, the
        // question can no longer be answered by anybody.
        captureAccountIdentityOnce()
        CredentialsVault.harvest()
    }

    /**
     * Captures `claude auth status` — the whole JSON, into the IDE safe — while the binary's own credentials
     * file still exists, because the very next line takes that file away.
     *
     * This is why the dashboard's Email and Organization rows were empty. `auth status` names the account
     * (`email`, `orgId`, `orgName`) only when it authenticates from its OWN store. Handed our credential
     * through the environment it answers `authMethod: oauth_token` and no identity at all, and `system/init`
     * carries the same anonymous account object — which is exactly why Plan and Provider filled in while
     * those two rows stayed blank. Harvest the credential first and there is nothing left to ask: the file was
     * the only thing that could answer.
     *
     * A login made in the user's own terminal is the case this covers; a sign-in through the card is already
     * in the right order. [AuthCli.status] does the filing, and only for a reply that names the account, so
     * this asks at most once per sign-in — with an answer banked there is nothing to ask. Blocking (it spawns
     * the binary); every caller of this is pooled-thread only.
     */
    private fun captureAccountIdentityOnce() {
        // Never from a test JVM. [CredentialsVault.credentialsFile] resolves the DEVELOPER's real home there,
        // so this would probe on the strength of their own login and file the answer in a throwaway safe —
        // the same reason the vault refuses to touch a real home under test. It also spawns the stand-in
        // binary, which has no `auth status` to answer with.
        if (ApplicationManager.getApplication()?.isUnitTestMode != false) return
        if (AuthCli.stored()?.email != null) return
        if (!CredentialsVault.credentialsFile().isFile) return
        val settings = ClaudeSettings.getInstance(project)
        val binary = ClaudeBinaryLocator.locate(settings.claudePath) ?: return
        // The RAW settings env: overlaying our own credential is precisely what makes the answer anonymous.
        AuthCli.status(binary, settings.resolveEnv())
    }

    /**
     * Whether this session has an identity to run as — checked BEFORE spawning anything, since that is a
     * question about what we hold, not about what the binary can do.
     *
     * The identity is exclusively: the vaulted subscription login, an API key in its provider slot, or a
     * credential the user wrote by hand into the Settings environment. Nothing held → logged out by
     * definition, and no process is started to re-ask a question we have already answered.
     *
     * A vaulted login whose access token has expired but whose refresh token has not counts as an identity —
     * it is one renewal away from live, and [renew] performs that renewal off the EDT at launch time.
     * Answering "signed out" here instead is what made every reboot end at the sign-in card.
     *
     * Deliberately does NOT harvest — see [absorbExistingLoginOnce] — and deliberately does not RENEW either:
     * this runs on the EDT from `start`, and renewal spawns a process.
     */
    fun hasCredential(settings: ClaudeSettings): Boolean {
        if (CredentialsVault.hasUsableToken()) return true
        if (CredentialsVault.canRenew()) return true
        if (SecretStore.get(SecretStore.OAUTH_TOKEN) != null) return true
        if (settings.getProviderApiKey(settings.provider).isNotBlank()) return true
        val explicit = settings.resolveEnv()
        if (SecretStore.API_KEY in explicit || SecretStore.OAUTH_TOKEN in explicit) return true
        // An explicit Log out outranks the binary's own login: otherwise clearing our safe changes nothing
        // the user can see, because the binary still holds one and the session starts straight back up.
        if (settings.state.signedOut) return false
        return binaryHoldsOwnLogin(settings)
    }

    /**
     * Last resort: does the BINARY hold a login of its own?
     *
     * This is what makes the plugin work off Linux. The vault only ever engages when there is a plaintext
     * `~/.claude/.credentials.json` to take custody of — which is the Linux situation. On macOS the binary
     * keeps its credentials in the **Keychain** and writes no such file, so a vault-only view of the world
     * concludes "signed out" no matter how many times the user signs in: the login card would reappear
     * immediately after every successful sign-in, forever. Windows behaves the same wherever the binary uses
     * a store rather than a file.
     *
     * So when we hold nothing, we ask instead of assuming. A binary with its own valid login is simply left
     * to use it: no vault, no config dir, no environment token — and, because it authenticates from its own
     * store, the dashboard gets the complete account and plan picture there too.
     *
     * Throttled hard ([OWN_LOGIN_TTL_MS]): this spawns a process, and the caller polls every few seconds.
     */
    private fun binaryHoldsOwnLogin(settings: ClaudeSettings): Boolean {
        val now = System.currentTimeMillis()
        ownLoginCheckedAt.takeIf { now - it < OWN_LOGIN_TTL_MS }?.let { return binaryOwnLogin }
        val binary = ClaudeBinaryLocator.locate(settings.claudePath) ?: return false
        // The RAW settings env, deliberately: overlaying our own credentials would be asking the binary
        // whether IT is signed in while handing it ours.
        val reply = AuthCli.status(binary, settings.resolveEnv())
        binaryOwnLogin = reply?.loggedIn == true
        ownLoginCheckedAt = now
        return binaryOwnLogin
    }

    /**
     * Brings the vaulted subscription login back to life when its access token has expired, BEFORE the launch
     * env is built. Blocking (process + network) — pooled thread only, which is why the session calls this
     * from `launch` and not from `start`.
     *
     * This is what makes a login survive a reboot. The access token the OAuth flow issues is good for hours;
     * the refresh token beside it in the safe is good for weeks and is rotated at every renewal. Without this
     * step the plugin held a perfectly persisted credential and still asked the user to sign in every
     * morning — the credential had not been lost, it had merely expired with nothing allowed to spend it.
     *
     * @return whether this launch has an identity to run as. A renewal that fails does not condemn the
     *   launch: the ttl cache is dropped first so the fallback question ("does the BINARY hold its own
     *   login?") is asked again — a renewal can sign the binary in even when we fail to take custody of what
     *   it wrote, which is the normal case wherever it uses an OS store instead of a file.
     */
    fun renew(binary: File, settings: ClaudeSettings): Boolean {
        // A sign-in owns `~/.claude/.credentials.json` from the browser leg until it is banked; renewing
        // underneath it would take away the very file the flow is about to write.
        if (signInInProgress()) return true
        if (!CredentialsVault.needsRenewal()) return true
        if (CredentialsVault.renew(binary, settings.resolveEnv())) {
            AccountProfile.invalidate()
            return true
        }
        ownLoginCheckedAt = 0
        return hasCredential(settings)
    }

    /**
     * Proactive auth check, off-EDT: `claude auth status --json` with the full launch env — so the answer
     * covers every identity the session can actually run on, in the order the binary itself resolves them:
     * an env credential (the PasswordSafe overlay / explicit Settings vars) first, its own credential store
     * (the full-consent `auth login`, shared with the terminal CLI) second. Not logged in by ANY of those →
     * the sign-in card is the first thing the tab shows, before a turn can fail on it.
     *
     * A probe that cannot run or parse yields a SYNTHETIC logged-out state rather than silence: the account
     * card's button must always exist and say something ("Sign in" that leads to an idempotent login beats
     * a button that omits itself and cannot be found).
     */
    fun probe() {
        val settings = ClaudeSettings.getInstance(project)
        val binary = ClaudeBinaryLocator.locate(settings.claudePath) ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val onOurEnv = AuthCli.status(binary, launchEnv()) ?: AuthCli.AuthState(loggedIn = false)
            status = identify(onOurEnv, binary, settings)
            onProbed(onOurEnv.loggedIn)
        }
    }

    /**
     * WHO the account is takes a second question, and this is why the dashboard's Email and Organization rows
     * were empty: asked with our credential in its environment the binary reports `authMethod: oauth_token`
     * and no identity at all. Asked with the RAW settings env it answers from its own store as `claude.ai` —
     * email, orgId, orgName, plan — which [AuthCli.status] files in the safe. `loggedIn` stays the first
     * answer's: that one describes the identity this session actually runs on.
     *
     * Skipped entirely once the first answer already named the account, and skipped when nobody is signed in:
     * an anonymous logged-OUT answer has no identity to go looking for, and asking anyway spawns a second
     * process per probe for nothing.
     */
    private fun identify(onOurEnv: AuthCli.AuthState, binary: File, settings: ClaudeSettings): AuthCli.AuthState {
        if (!onOurEnv.loggedIn || onOurEnv.email != null || onOurEnv.orgName != null) return onOurEnv
        val identity = AuthCli.status(binary, settings.resolveEnv())
            ?.takeIf { it.email != null || it.orgName != null }
            ?: AuthCli.stored()
        return onOurEnv.copy(
            email = identity?.email,
            orgId = identity?.orgId,
            orgName = identity?.orgName,
            apiProvider = onOurEnv.apiProvider ?: identity?.apiProvider,
            subscriptionType = onOurEnv.subscriptionType ?: identity?.subscriptionType,
        )
    }

    companion object {
        /**
         * How long the "does the binary hold its own login?" answer is trusted. It costs a process spawn and
         * the boot watcher asks every few seconds.
         */
        private const val OWN_LOGIN_TTL_MS = 60_000L
    }
}
