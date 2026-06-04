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
) {

    /** Callbacks fired from the reader thread — implementations must marshal any UI work onto the EDT. */
    interface Listener {
        /** The OAuth authorize URL, as soon as it appears (open the browser here). */
        fun onAuthUrl(url: String)

        /** The binary is now waiting for the authorization code on stdin (prompt the user, then [submitCode]). */
        fun onCodeRequested()

        /** The flow ended: [success] from the exit code (and a final-output sanity check), with a short [message]. */
        fun onResult(success: Boolean, message: String)
    }

    private val log = thisLogger()
    @Volatile private var process: PtyProcess? = null
    @Volatile private var urlSeen = false
    @Volatile private var promptSeen = false
    @Volatile private var finished = false

    /**
     * Spawns `claude auth login` under a PTY and starts streaming its output to [listener]. Returns false (so the
     * caller can fall back, e.g. to the IDE terminal) if the process can't be started. Safe to call off the EDT.
     */
    fun start(listener: Listener): Boolean {
        val builder = PtyProcessBuilder(arrayOf(binaryPath, "auth", "login"))
            .setEnvironment(env)            // pty4j replaces the env wholesale — [env] must already carry the base
            .setInitialColumns(1000)        // wide enough that the OAuth URL is emitted on a single line
            .setInitialRows(50)
            .setRedirectErrorStream(true)
        if (!cwd.isNullOrBlank()) builder.setDirectory(cwd)

        val proc = runCatching { builder.start() }.getOrElse {
            log.warn("Failed to spawn 'claude auth login' under a PTY", it)
            return false
        }
        process = proc
        Thread({ pump(proc, listener) }, "claude-login-reader").apply { isDaemon = true; start() }
        return true
    }

    /** Reads the PTY until EOF, firing URL/prompt signals, then resolves the result from the exit code. */
    private fun pump(proc: PtyProcess, listener: Listener) {
        val acc = StringBuilder()
        val buf = ByteArray(4096)
        runCatching {
            val input = proc.inputStream
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                acc.append(String(buf, 0, n, StandardCharsets.UTF_8))
                val text = acc.toString()
                if (!urlSeen) {
                    LoginOutputParser.extractAuthUrl(text)?.let { url -> urlSeen = true; listener.onAuthUrl(url) }
                }
                if (urlSeen && !promptSeen && LoginOutputParser.isCodePrompt(text)) {
                    promptSeen = true
                    listener.onCodeRequested()
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

    /** Writes the authorization [code] (plus a newline) to the binary's stdin. No-op if the process is gone. */
    fun submitCode(code: String) {
        val proc = process ?: return
        runCatching {
            proc.outputStream.apply {
                write((code.trim() + "\n").toByteArray(StandardCharsets.UTF_8))
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
