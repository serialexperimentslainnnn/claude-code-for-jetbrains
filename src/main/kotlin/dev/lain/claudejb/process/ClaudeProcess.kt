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
) {
    private val log = thisLogger()
    private val writeLock = Any()
    private val stdoutBuffer = StringBuilder()

    @Volatile
    private var handler: KillableProcessHandler? = null

    fun start() {
        val commandLine = GeneralCommandLine(binary.absolutePath)
            .withWorkDirectory(workDir)
            .withParameters(args)
            .withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

        val processHandler = KillableProcessHandler(commandLine)
        processHandler.setShouldDestroyProcessRecursively(true)
        processHandler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                when (outputType) {
                    ProcessOutputTypes.STDOUT -> consumeStdout(event.text)
                    ProcessOutputTypes.STDERR -> if (event.text.isNotBlank()) log.debug("claude stderr: ${event.text}")
                }
            }

            override fun processTerminated(event: ProcessEvent) = onTerminated(event.exitCode)
        })
        handler = processHandler
        processHandler.startNotify()
    }

    private fun consumeStdout(text: String) {
        val lines = ArrayList<String>()
        synchronized(stdoutBuffer) {
            stdoutBuffer.append(text)
            var newline = stdoutBuffer.indexOf("\n")
            while (newline >= 0) {
                lines.add(stdoutBuffer.substring(0, newline))
                stdoutBuffer.delete(0, newline + 1)
                newline = stdoutBuffer.indexOf("\n")
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

    /** Writes a single NDJSON record to the binary's stdin. Safe to call from any thread. */
    fun writeLine(line: String) {
        val stream = handler?.processInput ?: return
        synchronized(writeLock) {
            stream.write(line.toByteArray(StandardCharsets.UTF_8))
            stream.write('\n'.code)
            stream.flush()
        }
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
