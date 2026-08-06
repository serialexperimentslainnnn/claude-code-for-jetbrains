package dev.lain.claudejb.process

import com.intellij.openapi.diagnostic.thisLogger
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import java.nio.charset.StandardCharsets

/**
 * Drives `claude auth login` (the OAuth flow) **natively**, without dropping the user into an IDE terminal.
 *
 * Why a PTY: the interactive login is a TTY program (an Ink/React TUI). With piped stdio the binary prints
 * nothing and just blocks — exactly why our `--print` stream-json session can't host `/login`. So we allocate a
 * real pseudo-terminal with **pty4j** (bundled in the platform; the IDE's own terminal uses it) and let the
 * binary run its whole flow: it opens the browser, prints the authorize URL, and waits for the user to paste the
 * code shown on the callback page. The plugin's only job is the glue around that — open the browser, collect the
 * pasted code via a native dialog, and write it back to the PTY — surfaced through [Listener].
 *
 * Lifecycle: [start] spawns the process and reads it on a daemon thread (non-blocking). [submitCode] feeds the
 * code to stdin; [cancel] kills the process. The terminal is sized very wide so the long OAuth URL never wraps,
 * which keeps [LoginOutputParser.extractAuthUrl] a simple single-line match.
 */
class ClaudeLoginFlow(
    private val binaryPath: String,
    private val cwd: String?,
    private val env: Map<String, String>,
    /**
     * The subcommand to drive. `auth login` writes to the binary's own credential store;
     * `setup-token` prints a long-lived token instead (surfaced via [Listener.onToken]) so the caller can
     * keep it in the IDE's PasswordSafe and the binary's store stays empty.
     */
    private val args: List<String> = listOf("auth", "login"),
) {

    private companion object {
        /** Wide enough that the binary emits the OAuth authorize URL on ONE line — [pump] parses it per line. */
        const val PTY_COLUMNS = 1000

        /** Rows are irrelevant to parsing; a plausible terminal height keeps the child from reformatting output. */
        const val PTY_ROWS = 50

        /** PTY read chunk. One page — the login flow's whole output is a few KB. */
        const val READ_BUFFER_BYTES = 4096
    }

    /** Callbacks fired from the reader thread — implementations must marshal any UI work onto the EDT. */
    interface Listener {
        /** The OAuth authorize URL, as soon as it appears (open the browser here). */
        fun onAuthUrl(url: String)

        /** The binary is now waiting for the authorization code on stdin (prompt the user, then [submitCode]). */
        fun onCodeRequested()

        /**
         * A `setup-token` flow printed its long-lived token. Fired at most once, before [onResult]. The
         * value is a SECRET: store it (PasswordSafe) and nothing else — no logs, no transcript, no UI text.
         */
        fun onToken(token: String) {}

        /** The flow ended: [success] from the exit code (and a final-output sanity check), with a short [message]. */
        fun onResult(success: Boolean, message: String)
    }

    private val log = thisLogger()

    @Volatile private var process: PtyProcess? = null

    @Volatile private var urlSeen = false

    @Volatile private var promptSeen = false

    @Volatile private var tokenSeen = false

    @Volatile private var finished = false

    /**
     * Spawns `claude auth login` under a PTY and starts streaming its output to [listener]. Returns false (so the
     * caller can fall back, e.g. to the IDE terminal) if the process can't be started. Safe to call off the EDT.
     */
    fun start(listener: Listener): Boolean {
        val builder = PtyProcessBuilder((listOf(binaryPath) + args).toTypedArray())
            .setEnvironment(env) // pty4j replaces the env wholesale — [env] must already carry the base
            .setInitialColumns(PTY_COLUMNS) // wide enough that the OAuth URL is emitted on a single line
            .setInitialRows(PTY_ROWS)
            .setRedirectErrorStream(true)
        if (!cwd.isNullOrBlank()) builder.setDirectory(cwd)

        val proc = runCatching { builder.start() }.getOrElse {
            log.warn("Failed to spawn 'claude auth login' under a PTY", it)
            return false
        }
        process = proc
        Thread({ pump(proc, listener) }, "claude-login-reader").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /** Reads the PTY until EOF, firing URL/prompt signals, then resolves the result from the exit code. */
    private fun pump(proc: PtyProcess, listener: Listener) {
        val acc = StringBuilder()
        val buf = ByteArray(READ_BUFFER_BYTES)
        runCatching {
            val input = proc.inputStream
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                acc.append(String(buf, 0, n, StandardCharsets.UTF_8))
                val text = acc.toString()
                if (!urlSeen) {
                    LoginOutputParser.extractAuthUrl(text)?.let { url ->
                        urlSeen = true
                        listener.onAuthUrl(url)
                    }
                }
                if (urlSeen && !promptSeen && LoginOutputParser.isCodePrompt(text)) {
                    promptSeen = true
                    listener.onCodeRequested()
                }
                if (!tokenSeen) {
                    LoginOutputParser.extractSetupToken(text)?.let { token ->
                        tokenSeen = true
                        listener.onToken(token)
                    }
                }
            }
        }.onFailure { log.debug("login PTY reader stopped", it) }

        val exit = runCatching { proc.waitFor() }.getOrDefault(-1)
        val out = acc.toString()
        val success = exit == 0 && !LoginOutputParser.looksLikeFailure(out)
        finish(listener, success, LoginOutputParser.resultMessage(out, success))
    }

    private fun finish(listener: Listener, success: Boolean, message: String) {
        if (finished) return
        finished = true
        listener.onResult(success, message)
    }

    /**
     * Writes the authorization [code] to the binary's stdin, terminated with a CARRIAGE RETURN.
     *
     * `\r`, not `\n`, and it is the difference between working and hanging: the login TUI (Ink) puts the
     * TTY in raw mode, where the Enter key arrives as `\r` — that is what its input handler maps to
     * "submit". A trailing `\n` left the code sitting in the input field with the flow waiting forever,
     * which the user experienced as the card stuck on "Verifying".
     */
    fun submitCode(code: String) {
        val proc = process ?: return
        runCatching {
            proc.outputStream.apply {
                write((code.trim() + "\r").toByteArray(StandardCharsets.UTF_8))
                flush()
            }
        }.onFailure { log.warn("Failed to write the login code to the PTY", it) }
    }

    /** Aborts the flow and kills the process (e.g. the user canceled the code dialog). */
    fun cancel() {
        process?.destroy()
        process = null
    }
}
