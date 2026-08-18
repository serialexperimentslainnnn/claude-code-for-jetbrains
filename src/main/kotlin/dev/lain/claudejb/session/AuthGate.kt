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
 * What is known about this session's identity, as opposed to what could be found out by asking the binary.
 *
 * The third case is the one that matters and it is not a shrug: on a machine where the binary keeps its
 * credentials in an OS store rather than a file, only [AuthGate.hasCredential]'s probe can decide between the
 * other two, and that probe spawns a process. A caller that cannot afford to wait is told so instead of being
 * handed a "no" it would draw a sign-in card from — the answer would be wrong for exactly the users who are
 * already signed in.
 */
enum class Credential {
    /** An identity is held, or is one renewal away from live: a session may launch on it. */
    HELD,

    /** There is no identity: the sign-in card is the correct screen. */
    NONE,

    /** Not answerable without spawning the binary. Neither launch nor ask; let a pooled caller resolve it. */
    UNKNOWN,
}

/**
 * Who this session runs as: whether we hold an identity at all, whose it is, and keeping it alive.
 *
 * Separate from [ClaudeSession] because none of it is a turn — it is answered before a process exists and
 * re-answered while none is running. The session asks three questions — do we hold an identity
 * ([heldCredential] / [hasCredential]), can the one we hold be brought back to life ([renew]), and who is it
 * ([probe]) — and is told the answer; the harvesting, the throttles and the order in which the binary is
 * interrogated live here.
 *
 * **[heldCredential] and [canRenewCredential] are the only two the EDT may ask**: they read the safe, the
 * settings and the clock, and start no process on any path — which is why [heldCredential] answers
 * [Credential.UNKNOWN] instead of building a launch env that would source the user's shell. Everything else
 * here can spawn a process and is pooled-thread only, [hasCredential] included: it is the same question,
 * with the two undecidable branches actually resolved.
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
     * Whether this session has an identity to run as — checked BEFORE a session is launched, since that is a
     * question about what we hold, not about what a running binary can do.
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
     * renewal spawns a process and this is asked at launch time.
     *
     * Blocking: [Credential.UNKNOWN] is resolved by [binaryHoldsOwnLogin], which spawns the binary. Pooled
     * thread only. The EDT asks [heldCredential] and acts on the third answer instead.
     */
    fun hasCredential(settings: ClaudeSettings): Boolean = when (heldCredential(settings)) {
        Credential.HELD -> true
        Credential.NONE -> false
        Credential.UNKNOWN -> binaryHoldsOwnLogin(settings)
    }

    /**
     * The same question as [hasCredential], answered from what is already known — the safe, the settings and
     * the last probe result — and never by starting a process. Safe from the EDT.
     *
     * Every branch but the last decides outright, so an identity we hold ourselves is a `HELD` with no process
     * and no wait. What cannot be decided that way is [Credential.UNKNOWN], never `NONE`: the branch that would
     * settle it is "does the BINARY hold its own login?", and on a machine where it keeps credentials in an OS
     * store rather than a file (see [binaryHoldsOwnLogin]) EVERY signed-in user falls through to exactly that
     * branch. Reading a cache miss as "signed out" would therefore raise the sign-in card at the users who are
     * fine, for as long as it takes a pooled caller to answer.
     *
     * A configured source script is the second thing that cannot be settled here, for the same reason and with
     * the same answer: the credential it defines is real and counts, and finding out what it defines means
     * running the user's shell.
     */
    fun heldCredential(settings: ClaudeSettings): Credential {
        if (CredentialsVault.hasUsableToken()) return Credential.HELD
        if (CredentialsVault.canRenew()) return Credential.HELD
        if (SecretStore.get(SecretStore.OAUTH_TOKEN) != null) return Credential.HELD
        if (settings.getProviderApiKey(settings.provider).isNotBlank()) return Credential.HELD
        // Building the launch env SOURCES that script — `$SHELL -lc '. script && env'`, seconds of it. With
        // none configured the map costs nothing, so ask it; with one, defer rather than decide, because a
        // credential the script exports is an identity and calling it "none" here is the sign-in card raised
        // at a user who has one. [hasCredential] asks the whole question off the EDT.
        if (settings.state.sourceScript.isNotBlank()) return Credential.UNKNOWN
        val explicit = settings.resolveEnv()
        if (SecretStore.API_KEY in explicit || SecretStore.OAUTH_TOKEN in explicit) return Credential.HELD
        // An explicit Log out outranks the binary's own login: otherwise clearing our safe changes nothing
        // the user can see, because the binary still holds one and the session starts straight back up. It
        // does not outrank anything above it — those identities are configured deliberately and are not what
        // Log out clears.
        if (settings.state.signedOut) return Credential.NONE
        val probed = cachedBinaryLogin() ?: return Credential.UNKNOWN
        return if (probed) Credential.HELD else Credential.NONE
    }

    /**
     * Whether the vaulted login could be brought back to life without the user — the fact
     * [LoginDetection.resolve] needs to tell an access-token expiry that heals itself from one that ended the
     * identity. The same question [hasCredential] already counts as an identity, asked on its own so a failure
     * text can be answered without re-deriving the whole gate.
     *
     * Reads the safe and the clock, nothing else: no process, no network, no renewal — so it is safe from the
     * EDT, where a failed turn is surfaced. [CredentialsVault.canRenew] also answers false throughout the
     * cooldown a failed renewal arms, which is the honest answer here too: nothing is going to renew that
     * credential in the next few minutes.
     */
    fun canRenewCredential(): Boolean = CredentialsVault.canRenew()

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
        cachedBinaryLogin()?.let { return it }
        val now = System.currentTimeMillis()
        val binary = ClaudeBinaryLocator.locate(settings.claudePath) ?: return false
        // The RAW settings env, deliberately: overlaying our own credentials would be asking the binary
        // whether IT is signed in while handing it ours.
        val reply = AuthCli.status(binary, settings.resolveEnv())
        binaryOwnLogin = reply?.loggedIn == true
        ownLoginCheckedAt = now
        return binaryOwnLogin
    }

    /**
     * The last [binaryHoldsOwnLogin] answer while it is still inside [OWN_LOGIN_TTL_MS], or null when there is
     * none fresh enough to trust. Null is what makes [heldCredential] answer [Credential.UNKNOWN], and the
     * throttle is what makes a fresh answer free to both of them — the boot poll asks every few seconds and
     * the probe costs a process spawn.
     */
    private fun cachedBinaryLogin(): Boolean? =
        binaryOwnLogin.takeIf { System.currentTimeMillis() - ownLoginCheckedAt < OWN_LOGIN_TTL_MS }

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
        if (attemptRenewal(binary, settings)) return true
        // A renewal that failed does not condemn the launch: drop the ttl cache so the fallback question —
        // "does the BINARY hold its own login?" — is asked again rather than answered from a stale yes.
        ownLoginCheckedAt = 0
        return hasCredential(settings)
    }

    /**
     * Renews a credential the SERVER has rejected, whatever the clock thinks of it.
     *
     * **This is the other half of the bug [renew] could not see.** That one is gated on
     * [CredentialsVault.needsRenewal], which is `usableToken() == null && canRenew()` — and `usableToken()`
     * believes the blob's own `expiresAt`. A token that is revoked while still hours from expiring is therefore
     * "usable" by that test: nothing renews it, the turn fails with
     * `401 OAuth access token has been revoked`, and the next launch asks the same question and gets the same
     * answer. Observed exactly so — an `expiresAt` six hours in the future against a server that had already
     * revoked the token — and there was no way out of it but signing in by hand.
     *
     * So the expiry check is skipped here and only [CredentialsVault.canRenew] governs: is there a refresh
     * token, does it carry its scopes, is it itself still alive. That keeps the cooldown intact, which is what
     * stops a 401 in a loop from spawning a process every few seconds.
     *
     * @return true when a fresh credential is now in the safe — the caller's signal that relaunching is worth it.
     */
    fun renewRejected(binary: File, settings: ClaudeSettings): Boolean {
        if (signInInProgress()) return false
        if (!CredentialsVault.canRenew()) return false
        return attemptRenewal(binary, settings)
    }

    /**
     * The renewal itself, shared by the two triggers: the clock's ([renew]) and the server's ([renewRejected]).
     *
     * It answers ONLY "is there a fresh credential in the safe now" — the two callers need different things
     * from a failure and neither may inherit the other's. [renew] falls back to asking whether any identity
     * exists at all (a launch with an unrenewable credential can still run on the binary's own login);
     * [renewRejected] must not, because its caller relaunches on a true and relaunching into the same rejected
     * token is the loop this exists to break.
     */
    private fun attemptRenewal(binary: File, settings: ClaudeSettings): Boolean {
        if (!CredentialsVault.renew(binary, settings.resolveEnv())) return false
        AccountProfile.invalidate()
        return true
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
