package dev.lain.claudejb.process

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Key
import dev.lain.claudejb.protocol.ClaudeEvent
import dev.lain.claudejb.protocol.ProtocolParser
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Thin transport over one long-lived `claude` process driven in stream-json mode.
 *
 * stdout is delivered by the platform's process listener in arbitrary chunks; we buffer and split on
 * '\n' so each complete NDJSON line is parsed exactly once. stdin writes (user prompts and control
 * responses) are serialized through a lock since they come from multiple threads.
 */
class ClaudeProcess(
    private val binary: File,
    private val workDir: File,
    private val args: List<String>,
    private val onEvent: (ClaudeEvent) -> Unit,
    private val onTerminated: (exitCode: Int) -> Unit,
    private val nodeOverride: String? = null,
    private val extraEnv: Map<String, String> = emptyMap(),
) {
    private val log = thisLogger()
    private val writeLock = Any()
    private val stdoutBuffer = StringBuilder()

    private companion object {
        /**
         * Upper bound on a single buffered NDJSON line. stdout arrives in arbitrary chunks and we only
         * flush on '\n'; a stream that never emits a newline would grow `stdoutBuffer` without bound and
         * eventually OOM. If the trailing partial line exceeds this, we drop it (see `consumeStdout`).
         */
        const val MAX_LINE_LENGTH = 16 * 1024 * 1024 // 16 MiB

        /**
         * How much of an offending NDJSON line reaches the log. A protocol line can carry a whole file's
         * contents, so logging it whole would dump user data into idea.log; enough to identify the frame is
         * all a diagnosis needs.
         */
        const val LOG_PREVIEW_CHARS = 200
        const val STDIN_LOG_PREVIEW_CHARS = 120
    }

    @Volatile
    private var handler: KillableProcessHandler? = null

    /**
     * Launches the `claude` process and begins streaming.
     *
     * May throw if the process cannot be created (e.g. `ExecutionException`/`ProcessNotCreatedException`
     * from invalid args, an unresolved node interpreter, or insufficient permissions). The exception is
     * propagated to the caller (who is expected to wrap this in `runCatching`, notify, and abort); on
     * failure `handler` is left null so `isRunning()` reports false.
     */
    fun start() {
        // On Windows an npm `.cmd` shim must be driven as `node cli.js` (see ClaudeBinaryLocator.resolveNodeScript):
        // launching the shim through cmd.exe breaks streaming stdio and arg quoting.
        val nodeScript = ClaudeBinaryLocator.resolveNodeScript(binary)
        val commandLine = (
            if (nodeScript != null) {
                GeneralCommandLine(ClaudeBinaryLocator.locateNode(binary, nodeOverride))
                    .withParameters(nodeScript.absolutePath).withParameters(args)
            } else {
                GeneralCommandLine(binary.absolutePath).withParameters(args)
            }
            )
            .withWorkDirectory(workDir)
            .withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withEnvironment(extraEnv)

        // KillableProcessHandler's constructor starts the OS process; if it throws, `handler` stays null
        // and we let the exception propagate to the caller.
        val processHandler = KillableProcessHandler(commandLine)
        processHandler.setShouldDestroyProcessRecursively(true)
        processHandler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                when (outputType) {
                    ProcessOutputTypes.STDOUT -> consumeStdout(event.text)
                    ProcessOutputTypes.STDERR -> if (event.text.isNotBlank()) log.debug("claude stderr: ${event.text}")
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                log.info("claude terminated, exitCode=${event.exitCode}")
                onTerminated(event.exitCode)
            }
        })
        handler = processHandler
        processHandler.startNotify()
        // Args may carry paths/data, so we log only their count — never the contents or env.
        log.info("claude started: ${binary.name} (${args.size} args)")
    }

    // Suppressed, not silenced. detekt is right that a broad catch is usually a smell; it is wrong here, for
    // the reason spelled out at the catch itself — this is the reader loop for the entire session, and one bad
    // line must not take the thread down with it. Scoped to this function so it cannot drift into covering a
    // second, unexamined catch somewhere else in the class.
    @Suppress("TooGenericExceptionCaught")
    private fun consumeStdout(text: String) {
        val lines = ArrayList<String>()
        synchronized(stdoutBuffer) {
            stdoutBuffer.append(text)
            // Walk forward from `start` once over this chunk, slicing out each complete line; the partial
            // trailing line stays in the buffer. We compact the consumed prefix once at the end instead of
            // per line, so a chunk of N chars costs O(N) (indexOf resumes from `start`, no per-line shift).
            var start = 0
            var newline = stdoutBuffer.indexOf("\n", start)
            while (newline >= 0) {
                lines.add(stdoutBuffer.substring(start, newline))
                start = newline + 1
                newline = stdoutBuffer.indexOf("\n", start)
            }
            if (start > 0) stdoutBuffer.delete(0, start)
            // Guard against an unbounded partial line (a stream that never emits '\n'): once the
            // remaining buffer exceeds the cap it cannot be a legitimate NDJSON record, so drop it
            // rather than let memory grow without bound.
            if (stdoutBuffer.length > MAX_LINE_LENGTH) {
                log.warn("Dropping oversized claude stdout line (${stdoutBuffer.length} bytes > $MAX_LINE_LENGTH cap, no newline)")
                stdoutBuffer.setLength(0)
            }
        }
        for (line in lines) {
            try {
                ProtocolParser.parse(line).forEach(onEvent)
            } catch (e: Exception) {
                // Deliberately broad, and deliberately NOT Throwable. Broad because this is the reader loop for
                // the whole session: one unparseable line must not kill the thread that carries every subsequent
                // event. Not Throwable because that also swallows OutOfMemoryError and StackOverflowError, and
                // continuing to read after the JVM has told us it is out of memory turns a clear failure into a
                // mysterious one. Errors propagate and kill the thread, which is the correct outcome for them.
                log.warn("Failed to handle claude line: ${line.take(LOG_PREVIEW_CHARS)}", e)
            }
        }
    }

    /**
     * Writes a single NDJSON record to the binary's stdin. Safe to call from any thread.
     *
     * @return true if the line was written, false if it was dropped because stdin is dead
     *         (process not started or already terminated). The Boolean lets callers react;
     *         it is safe to ignore for fire-and-forget writes.
     */
    fun writeLine(line: String): Boolean {
        val stream = handler?.processInput ?: run {
            log.warn("Dropping line to dead claude stdin: ${line.take(STDIN_LOG_PREVIEW_CHARS)}")
            return false
        }
        synchronized(writeLock) {
            stream.write(line.toByteArray(StandardCharsets.UTF_8))
            stream.write('\n'.code)
            stream.flush()
        }
        return true
    }

    fun isRunning(): Boolean = handler?.let { !it.isProcessTerminated } ?: false

    /**
     * Ends the process: EOF on stdin so the binary can finish what it is doing, then the tree destroyed.
     *
     * **The teardown does not run on the caller's thread, and that is the whole reason this method exists.**
     * Every caller reaches it from the UI — closing a tab, restarting a session, disposing one — and both
     * halves are blocking I/O, which the platform's
     * [threading model](https://plugins.jetbrains.com/docs/intellij/threading-model.html) puts off limits
     * there. Closing stdin flushes a pipe, and it takes the same [writeLock] a producer already blocked on a
     * full pipe is holding; destroying
     * the process runs `KillableProcessHandler.destroyProcessImpl`, which flushes that pipe again and then —
     * because the handler is built with `setShouldDestroyProcessRecursively(true)` — walks and signals the
     * whole process TREE, which every OS answers by enumerating its process table. Seconds of it, on the
     * thread that repaints. That is what a closed chat tab used to spend before it could redraw the tab bar:
     * the pill of the chat that had just been closed stayed on screen, greyed, until the kill returned.
     *
     * Nothing waits for the result, and nothing needs to: the handler is dropped **before** the teardown is
     * even scheduled, so from the first line on [isRunning] answers false and [writeLine] refuses, exactly as
     * if the process were already gone. Calling it twice is a no-op.
     */
    fun terminate() {
        val dying = handler ?: return
        handler = null
        val submitted = runCatching {
            ApplicationManager.getApplication().executeOnPooledThread { endProcess(dying) }
        }
        // The pool is gone, which means the application itself is being disposed. Doing it inline blocks a
        // shutdown thread; not doing it leaves a `claude` outliving the IDE that spawned it.
        if (submitted.isFailure) {
            log.warn("Pooled teardown unavailable, killing claude on ${Thread.currentThread().name}")
            endProcess(dying)
        }
    }

    /** EOF first — it is what lets the binary exit on its own — then the tree. Never on the EDT: see [terminate]. */
    private fun endProcess(dying: KillableProcessHandler) {
        runCatching { synchronized(writeLock) { dying.processInput?.close() } }
        dying.destroyProcess()
    }
}
