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
        const val MAX_LINE_LENGTH = 16 * 1024 * 1024

        const val LOG_PREVIEW_CHARS = 200
        const val STDIN_LOG_PREVIEW_CHARS = 120
    }

    @Volatile
    private var handler: KillableProcessHandler? = null

    fun start() {
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
        log.info("claude started: ${binary.name} (${args.size} args)")
    }

    // Suppressed, not silenced. detekt is right that a broad catch is usually a smell; it is wrong here, for
    @Suppress("TooGenericExceptionCaught")
    private fun consumeStdout(text: String) {
        val lines = ArrayList<String>()
        synchronized(stdoutBuffer) {
            stdoutBuffer.append(text)
            var start = 0
            var newline = stdoutBuffer.indexOf("\n", start)
            while (newline >= 0) {
                lines.add(stdoutBuffer.substring(start, newline))
                start = newline + 1
                newline = stdoutBuffer.indexOf("\n", start)
            }
            if (start > 0) stdoutBuffer.delete(0, start)
            if (stdoutBuffer.length > MAX_LINE_LENGTH) {
                log.warn("Dropping oversized claude stdout line (${stdoutBuffer.length} bytes > $MAX_LINE_LENGTH cap, no newline)")
                stdoutBuffer.setLength(0)
            }
        }
        for (line in lines) {
            try {
                ProtocolParser.parse(line).forEach(onEvent)
            } catch (e: Exception) {
                log.warn("Failed to handle claude line: ${line.take(LOG_PREVIEW_CHARS)}", e)
            }
        }
    }

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

    fun terminate() {
        val dying = handler ?: return
        handler = null
        val submitted = runCatching {
            ApplicationManager.getApplication().executeOnPooledThread { endProcess(dying) }
        }
        if (submitted.isFailure) {
            log.warn("Pooled teardown unavailable, killing claude on ${Thread.currentThread().name}")
            endProcess(dying)
        }
    }

    private fun endProcess(dying: KillableProcessHandler) {
        runCatching { synchronized(writeLock) { dying.processInput?.close() } }
        dying.destroyProcess()
    }
}
