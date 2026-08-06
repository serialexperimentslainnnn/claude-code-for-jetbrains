package dev.lain.claudejb.session

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.lain.claudejb.process.ClaudeBinaryLocator
import dev.lain.claudejb.process.ClaudeLoginFlow
import dev.lain.claudejb.process.TerminalLauncher
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.Provider
import java.io.File

/**
 * Owns the OAuth sign-in flow, which is a whole subsystem in its own right and has nothing to do with running a
 * chat turn: the `--print` stream-json session has no TTY and cannot host an interactive login, so signing in
 * happens *outside* the session entirely, through three independent paths.
 *
 * Extracted from [ClaudeSession] so the orchestrator stays an orchestrator. The three paths are tried in order,
 * and each is a **real** fallback rather than a dead end:
 *
 *  1. an **IDE terminal tab** running `claude auth login` ([openTerminal]) — preferred: the binary drives its whole
 *     TUI visibly and captures the OAuth callback automatically, usually with nothing to paste;
 *  2. the **native PTY flow** ([startNativePtyFlow]) when the terminal can't open (Terminal plugin disabled, or its
 *     API moved again) — same binary, same flow, headless, with a code dialog if it asks for one;
 *  3. only if both fail, a notice carrying the exact command to run by hand.
 *
 * That ordering is load-bearing and was bought at a cost: before 4.4.1 the terminal path was called
 * unconditionally and, on a current IDE, failed *silently* (see [TerminalLauncher.openAndRunCommand]), so `/login`
 * always landed on step 3 and the PTY flow was unreachable code.
 *
 * Threading: [start] may be called from anywhere; everything that touches a dialog or the terminal is hopped onto
 * the EDT through the injected [edt] dispatcher. The [ClaudeLoginFlow] callbacks arrive on its own reader thread
 * and hop the same way.
 */
class LoginCoordinator(
    private val project: Project,
    private val edt: (() -> Unit) -> Unit,
    private val notifyInfo: (String) -> Unit,
    private val notifyError: (String) -> Unit,
    private val notifyMissingBinary: () -> Unit,
    private val restartSession: () -> Unit,
) {

    private val log = thisLogger()

    /**
     * The sign-in card, when a chat panel is attached. This seam is what makes the flow NATIVE: with a UI
     * present the whole OAuth dance runs inside the plugin (card → browser → code back into the card) and
     * neither a terminal tab nor a modal dialog ever appears. Registered by JcefChatPanel in `init`,
     * detached in `dispose`; with none attached the legacy paths below still work.
     */
    interface LoginUi {
        /** The OAuth authorize URL: show it on the card (the browser is opened separately). */
        fun onAuthUrl(url: String)

        /** The binary now waits for the authorization code — the card swaps to its code input. */
        fun onCodeRequested()

        /** The flow ended. On success the session restart follows; on failure [message] goes on the card. */
        fun onLoginResult(success: Boolean, message: String)
    }

    @Volatile private var ui: LoginUi? = null

    fun attachUi(loginUi: LoginUi) {
        ui = loginUi
    }

    fun detachUi(loginUi: LoginUi) {
        if (ui === loginUi) ui = null
    }

    /**
     * Set once we've offered the sign-in for the current auth-failure streak, so a retry storm doesn't fire one
     * notification per failed turn. Cleared by [onCleanResult] on the next non-error result — which is also what
     * clears it after a successful login, since the restart's first clean turn gets there.
     */
    @Volatile private var prompted = false

    /** The in-flight native login flow, and the OAuth URL it surfaced (used as a hint in the code dialog). */
    @Volatile private var flow: ClaudeLoginFlow? = null

    @Volatile private var authUrl: String? = null

    /**
     * True while a sign-in is actually in flight.
     *
     * Load-bearing for [ClaudeSession.refreshBootState], which harvests the credentials file every few
     * seconds: `claude auth login` WRITES that file as it completes, so harvesting mid-flow deletes the
     * credential out from under the binary and the browser leg silently fails — leaving the code-paste
     * fallback as the only route that ever worked. Nothing touches that file while this is true.
     */
    val inProgress: Boolean get() = signingIn

    /**
     * Deliberately NOT derived from `flow != null`. That was the bug: `flow` is assigned only AFTER the PTY
     * has been started, and cleared at the TOP of the result handler, leaving two windows in which a sign-in
     * was running while this read false — long enough for the watcher to harvest (and now stop the session)
     * exactly as the binary was writing the credential it had just been granted. This is raised before
     * anything spawns and lowered only when the flow is completely done with.
     */
    @Volatile private var signingIn = false

    /**
     * Feeds the card-entered authorization code to the running flow, with a WATCHDOG: if the flow gives no
     * verdict within [VERIFY_TIMEOUT_MS], it is cancelled and the card told so. A submit that hangs must
     * end in words, never in a spinner the user has to give up on — that exact dead end was observed live
     * (the raw-TTY Enter defect) and the escape has to exist independently of that fix being right.
     */
    fun submitCode(code: String) {
        val current = flow ?: return
        current.submitCode(code)
        verifyTimer?.stop()
        verifyTimer = javax.swing.Timer(VERIFY_TIMEOUT_MS) {
            if (flow === current) {
                cancelLogin()
                ui?.onLoginResult(false, "No answer after submitting the code. Try again — or use the API-key route.")
            }
        }.apply {
            isRepeats = false
            start()
        }
    }

    private var verifyTimer: javax.swing.Timer? = null

    /** The card's Cancel: kill the in-flight flow, if any. */
    fun cancelLogin() {
        verifyTimer?.stop()
        verifyTimer = null
        flow?.cancel()
        flow = null
        authUrl = null
        signingIn = false
    }

    /** A turn ended cleanly: the auth-failure streak is over, so the next failure may prompt again. */
    fun onCleanResult() {
        prompted = false
    }

    /** Offer the sign-in at most once per auth-failure streak (see [prompted]). */
    fun maybePrompt() {
        // Only the Anthropic provider uses OAuth login. On a third-party provider an auth failure means a
        // wrong/missing API key, not a missing login — don't offer the Anthropic sign-in there.
        if (ClaudeSettings.getInstance(project).provider != Provider.ANTHROPIC) return
        if (prompted) return
        prompted = true
        notifyLoginNeeded()
    }

    /** A warning notification whose action runs the login flow. */
    private fun notifyLoginNeeded() {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(ClaudeSession.NOTIFICATION_GROUP)
            .createNotification(
                "Claude Code",
                "You don't seem to be logged in. Sign in to Claude to continue.",
                NotificationType.WARNING,
            )
            .addAction(NotificationAction.createSimple("Sign in") { start() })
            .notify(project)
    }

    /** Runs the OAuth login through the three paths documented on this class. Also the target of a typed `/login`. */
    fun start() {
        val settings = ClaudeSettings.getInstance(project)
        // /login is the Anthropic OAuth flow — only meaningful for the official Anthropic provider. For a
        // third-party provider, auth is its own API key (configured in Settings), not an OAuth login.
        if (settings.provider != Provider.ANTHROPIC) {
            notifyInfo(
                "Sign-in is only for the Anthropic provider. You're on ${settings.provider.label} — " +
                    "set its API key in Settings instead.",
            )
            return
        }
        val binary = ClaudeBinaryLocator.locate(settings.claudePath) ?: run {
            notifyMissingBinary()
            return
        }
        // PRIMARY: the in-card native flow, whenever a card exists to drive it. The terminal is the fallback
        // (PTY spawn failed), the "run it yourself" notice the last resort — the exact inverse of the pre-5
        // ordering, which dropped the user into a terminal tab as the happy path.
        val cardUi = ui
        if (cardUi != null && startCardFlow(binary, cardUi)) return
        edt {
            if (openTerminal(binary)) return@edt
            log.info("IDE terminal unavailable for /login — falling back to the dialog-driven PTY flow")
            if (startNativePtyFlow(binary)) {
                notifyInfo("Signing in… your browser should open. Approve access there to finish.")
                return@edt
            }
            notifyError(
                "Couldn't start the sign-in flow. Run this in a terminal, then restart the chat:\n" +
                    TerminalLauncher.loginCommand(binary.absolutePath),
            )
        }
    }

    /**
     * The native, card-driven subscription flow: `claude auth login` under a PTY, every step surfaced on
     * the sign-in card and nothing else on screen.
     *
     * `auth login`, NOT `setup-token`, and the difference is the OAuth CONSENT: setup-token mints a
     * reduced-scope token, and the scopes it drops (file upload among them) are ones Claude Code actually
     * exercises — a pasted attachment travels through "Upload files on your behalf". So the full login it
     * is, with its cost stated rather than hidden: the credentials land in the binary's own store, SHARED
     * with the terminal CLI (OS keychain on macOS, DPAPI on Windows, a 0600 file on Linux) — subscription
     * identity is no longer separable from the CLI's, and Log out signs the terminal out too. The
     * PasswordSafe remains the home of the API-key route, which stays fully plugin-owned.
     *
     * Returns false when the PTY could not spawn, so [start] falls back to the terminal.
     */
    private fun startCardFlow(binary: File, cardUi: LoginUi): Boolean {
        // pty4j REPLACES the child env wholesale — merge the base in or the binary loses PATH/HOME.
        val env = System.getenv() + ClaudeSettings.getInstance(project).resolveEnv()
        // BEFORE the spawn: from here until the flow is fully settled, nothing else may touch the
        // credentials file — `auth login` finishes by writing it, and taking it away mid-flow is what broke
        // the browser leg and left code-paste as the only route that worked.
        signingIn = true
        val ptyFlow = ClaudeLoginFlow(binary.absolutePath, project.basePath, env, args = listOf("auth", "login"))
        val started = ptyFlow.start(object : ClaudeLoginFlow.Listener {
            override fun onAuthUrl(url: String) {
                authUrl = url
                // NO BrowserUtil.browse: the binary opens the browser itself, and THAT is the tab that
                // completes the sign-in. Opening it again from here just added a second tab on top.
                //
                // Removing this once looked like it broke the browser leg, which is why it came back for a
                // build. It did not: what broke it was the boot watcher harvesting (and deleting)
                // ~/.claude/.credentials.json mid-flow — `auth login` finishes by writing exactly that file.
                // With that race closed (LoginCoordinator.signingIn) the binary's own tab works, so ours is
                // pure duplication. The card's "Open your browser" button remains for the case where the
                // binary's attempt genuinely fails.
                edt { cardUi.onAuthUrl(url) }
            }

            override fun onCodeRequested() = edt { cardUi.onCodeRequested() }

            override fun onToken(token: String) {
                // `auth login` normally prints none; if a flow variant ever does, the safe is the one
                // place it may go. Never logged, never shown, never echoed back.
                dev.lain.claudejb.settings.SecretStore.set(dev.lain.claudejb.settings.SecretStore.OAUTH_TOKEN, token)
            }

            override fun onResult(success: Boolean, message: String) = edt {
                verifyTimer?.stop()
                verifyTimer = null
                flow = null
                authUrl = null
                if (success) {
                    // `auth login` wrote its credentials to ~/.claude/.credentials.json — plaintext on
                    // Linux and readable by anything running as the user. Take it into the IDE's encrypted
                    // safe and delete it immediately; the launch path writes it back 0600 for the session
                    // and harvests it again at teardown, so it is on disk only while a session runs.
                    // OUR harvest, while the guard still holds — so the credential lands in the safe here
                    // rather than being raced for by the watcher.
                    val vaulted = dev.lain.claudejb.process.CredentialsVault.harvest()
                    signingIn = false
                    cardUi.onLoginResult(true, message)
                    notifyInfo(
                        if (vaulted) {
                            "Signed in. Your credentials were moved into the IDE's password safe."
                        } else {
                            "Signed in to Claude."
                        },
                    )
                    restartSession() // relaunch on the fresh credentials
                } else {
                    signingIn = false
                    cardUi.onLoginResult(false, message)
                }
            }
        })
        if (started) flow = ptyFlow else signingIn = false // a PTY that never spawned holds no guard
        return started
    }

    /**
     * Opens an IDE terminal running `claude auth login`. Preferred over the PTY flow because the binary drives its
     * whole interactive TUI visibly and captures the OAuth callback automatically (usually nothing to paste).
     * Always uses the binary's absolute path so a GUI IDE that didn't inherit the user's login `$PATH` still
     * launches the right binary. Returns whether the terminal actually opened, so [start] can fall through to the
     * native PTY flow instead of dead-ending on a "do it yourself" notice.
     */
    private fun openTerminal(binary: File): Boolean {
        val opened = TerminalLauncher.openAndRunCommand(
            project,
            TerminalLauncher.loginArgv(binary.absolutePath),
            "claude login",
        )
        if (opened) {
            // We can't observe the terminal's completion, so offer a one-click restart to pick up the
            // new auth once the user finishes signing in there.
            NotificationGroupManager.getInstance()
                .getNotificationGroup(ClaudeSession.NOTIFICATION_GROUP)
                .createNotification(
                    "Claude Code",
                    "Finish signing in in the terminal — the browser opens automatically. " +
                        "When it confirms you're logged in, restart the chat to use it.",
                    NotificationType.INFORMATION,
                )
                .addAction(NotificationAction.createSimple("Restart chat") { restartSession() })
                .notify(project)
        }
        return opened
    }

    /**
     * Native PTY fallback: runs `claude auth login` under a pseudo-terminal ([ClaudeLoginFlow]) with no IDE terminal
     * involved, so signing in still works when the bundled Terminal plugin is disabled or its API has moved again.
     * The binary opens the browser itself; if it asks for a code we scrape the prompt and collect it in a dialog.
     * Returns whether the PTY spawned.
     */
    private fun startNativePtyFlow(binary: File): Boolean {
        // pty4j REPLACES the child env wholesale (unlike ClaudeProcess, which inherits the parent's and layers
        // extras on top), so the base environment has to be merged in here or the binary loses PATH/HOME entirely.
        val env = System.getenv() + ClaudeSettings.getInstance(project).resolveEnv()
        signingIn = true // same guard as the card flow, raised before the spawn
        val ptyFlow = ClaudeLoginFlow(binary.absolutePath, project.basePath, env)
        val started = ptyFlow.start(object : ClaudeLoginFlow.Listener {
            override fun onAuthUrl(url: String) {
                // Same as the card flow: the binary's own tab is the one that completes the sign-in, so we
                // do not open a second. The URL is kept as the hint shown in the code dialog.
                authUrl = url
            }

            override fun onCodeRequested() = edt { promptForCode(ptyFlow) }

            override fun onResult(success: Boolean, message: String) = edt {
                flow = null
                authUrl = null
                if (success) {
                    // Harvest under the guard, then lower it — same ordering as the card flow.
                    dev.lain.claudejb.process.CredentialsVault.harvest()
                    signingIn = false
                    notifyInfo(message)
                    restartSession() // pick up the new credentials
                } else {
                    signingIn = false
                    notifyError(message)
                }
            }
        })
        if (started) flow = ptyFlow else signingIn = false
        return started
    }

    /** EDT-only. Asks for the authorization code and feeds it to the running [ClaudeLoginFlow] (or cancels it). */
    private fun promptForCode(ptyFlow: ClaudeLoginFlow) {
        val urlHint = authUrl?.let { "\n\nIf the browser didn't open, visit:\n$it" }.orEmpty()
        val code = Messages.showInputDialog(
            project,
            "Approve access in your browser, then paste the authorization code here.$urlHint",
            "Sign in to Claude",
            null,
        )
        if (code.isNullOrBlank()) {
            ptyFlow.cancel()
            flow = null
            notifyInfo("Login canceled.")
        } else {
            ptyFlow.submitCode(code.trim())
        }
    }

    private companion object {
        /**
         * How long a submitted code may sit unanswered before the flow is cancelled and the card says so.
         * Generous — the exchange is one HTTPS round-trip — but bounded: past this, waiting longer is not
         * going to produce a different outcome.
         */
        const val VERIFY_TIMEOUT_MS = 45_000
    }
}
