package dev.lain.claudejb.process

import com.intellij.openapi.diagnostic.thisLogger
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import java.nio.charset.StandardCharsets

class ClaudeLoginFlow(
    private val binaryPath: String,
    private val cwd: String?,
    private val env: Map<String, String>,
    private val args: List<String> = listOf("auth", "login"),
) {

    private companion object {
        const val PTY_COLUMNS = 1000

        const val PTY_ROWS = 50

        const val READ_BUFFER_BYTES = 4096
    }

    interface Listener {
        fun onAuthUrl(url: String)

        fun onCodeRequested()

        fun onToken(token: String) {}

        fun onResult(success: Boolean, message: String)
    }

    private val log = thisLogger()

    @Volatile private var process: PtyProcess? = null

    @Volatile private var urlSeen = false

    @Volatile private var promptSeen = false

    @Volatile private var tokenSeen = false

    @Volatile private var finished = false

    fun start(listener: Listener): Boolean {
        val builder = PtyProcessBuilder((listOf(binaryPath) + args).toTypedArray())
            .setEnvironment(env)
            .setInitialColumns(PTY_COLUMNS)
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
        log.debug("claude login finished (exit=$exit):\n${LoginOutputParser.redactSecrets(out)}")
        val success = exit == 0 && !LoginOutputParser.looksLikeFailure(out)
        finish(listener, success, LoginOutputParser.resultMessage(out, success))
    }

    private fun finish(listener: Listener, success: Boolean, message: String) {
        if (finished) return
        finished = true
        listener.onResult(success, message)
    }

    fun submitCode(code: String) {
        val proc = process ?: return
        runCatching {
            proc.outputStream.apply {
                write((code.trim() + "\r").toByteArray(StandardCharsets.UTF_8))
                flush()
            }
        }.onFailure { log.warn("Failed to write the login code to the PTY", it) }
    }

    fun cancel() {
        process?.destroy()
        process = null
    }
}
