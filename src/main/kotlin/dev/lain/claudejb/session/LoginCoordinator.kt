package dev.lain.claudejb.session

import com.intellij.ide.BrowserUtil
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
     * Set once we've offered the sign-in for the current auth-failure streak, so a retry storm doesn't fire one
     * notification per failed turn. Cleared by [onCleanResult] on the next non-error result — which is also what
     * clears it after a successful login, since the restart's first clean turn gets there.
     */
    @Volatile private var prompted = false

    /** The in-flight native login flow, and the OAuth URL it surfaced (used as a hint in the code dialog). */
    @Volatile private var flow: ClaudeLoginFlow? = null

    @Volatile private var authUrl: String? = null

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
        edt {
            if (openTerminal(binary)) return@edt
            log.info("IDE terminal unavailable for /login — falling back to the native PTY flow")
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
        val ptyFlow = ClaudeLoginFlow(binary.absolutePath, project.basePath, env)
        val started = ptyFlow.start(object : ClaudeLoginFlow.Listener {
            override fun onAuthUrl(url: String) {
                authUrl = url
                edt { BrowserUtil.browse(url) }
            }

            override fun onCodeRequested() = edt { promptForCode(ptyFlow) }

            override fun onResult(success: Boolean, message: String) = edt {
                flow = null
                authUrl = null
                if (success) {
                    notifyInfo(message)
                    restartSession() // pick up the new credentials
                } else {
                    notifyError(message)
                }
            }
        })
        if (started) flow = ptyFlow
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
}
