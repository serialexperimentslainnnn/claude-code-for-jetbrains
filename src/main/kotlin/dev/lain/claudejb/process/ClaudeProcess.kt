package dev.lain.claudejb.process

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
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
            if (nodeScript != null)
                GeneralCommandLine(ClaudeBinaryLocator.locateNode(binary, nodeOverride))
                    .withParameters(nodeScript.absolutePath).withParameters(args)
            else
                GeneralCommandLine(binary.absolutePath).withParameters(args)
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
            } catch (t: Throwable) {
                log.warn("Failed to handle claude line: ${line.take(200)}", t)
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
            log.warn("Dropping line to dead claude stdin: ${line.take(120)}")
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

    /** Closes stdin (EOF) so the binary can shut down gracefully after finishing in-flight work. */
    fun closeStdin() {
        runCatching { synchronized(writeLock) { handler?.processInput?.close() } }
    }

    fun destroy() {
        handler?.destroyProcess()
    }
}
