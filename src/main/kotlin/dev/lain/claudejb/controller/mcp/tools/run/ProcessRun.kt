package dev.lain.claudejb.controller.mcp.tools.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import dev.lain.claudejb.model.mcp.ToolException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

internal class ProcessRun(private val project: Project) {

    private class Pending {
        val started = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Int>()
        val external = AtomicReference<ExternalTaskOutput?>()
    }

    suspend fun run(
        settings: RunnerAndConfigurationSettings,
        tail: OutputTail,
        executorId: String = DefaultRunExecutor.EXECUTOR_ID,
        onHandler: (ProcessHandler) -> Unit = {},
    ): Int {
        val pending = Pending()
        val connection = project.messageBus.connect()
        try {
            connection.subscribe(ExecutionManager.EXECUTION_TOPIC, listener(settings, tail, onHandler, pending))
            withContext(Dispatchers.EDT) { launch(settings, executorId) }
            withTimeoutOrNull(START_TIMEOUT_MILLIS) { pending.started.await() }
                ?: throw ToolException(
                    "${settings.name} did not start within ${START_TIMEOUT_MILLIS / MILLIS} s: a before-launch task may have " +
                        "failed, or the IDE is asking a question about it",
                )
            return pending.exited.await()
        } finally {
            connection.disconnect()
            pending.external.get()?.detach()
        }
    }

    private fun launch(settings: RunnerAndConfigurationSettings, executorId: String) {
        val executor = ExecutorRegistry.getInstance().getExecutorById(executorId)
            ?: throw ToolException("this IDE has no executor $executorId; is its plugin (Coverage, Profiler) installed?")
        val environment = try {
            ExecutionEnvironmentBuilder.create(executor, settings).activeTarget().build()
        } catch (e: ExecutionException) {
            throw ToolException("${settings.name} cannot run: ${e.message}", e)
        }
        ExecutionManager.getInstance(project).restartRunProfile(environment)
    }

    private fun listener(
        settings: RunnerAndConfigurationSettings,
        tail: OutputTail,
        onHandler: (ProcessHandler) -> Unit,
        pending: Pending,
    ): ExecutionListener = object : ExecutionListener {

        override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
            if (env.runnerAndConfigurationSettings !== settings) return
            pending.external.set(ExternalTaskOutput.attach(handler, tail))
            if (pending.external.get() == null) handler.addProcessListener(textListener(tail))
            onHandler(handler)
            pending.started.complete(Unit)
        }

        override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
            if (env.runnerAndConfigurationSettings === settings) pending.exited.complete(exitCode)
        }

        override fun processNotStarted(executorId: String, env: ExecutionEnvironment, cause: Throwable?) {
            if (env.runnerAndConfigurationSettings !== settings) return
            val reason = cause?.message?.let { ": $it" } ?: ""
            pending.started.completeExceptionally(ToolException("${settings.name} did not start$reason", cause))
        }
    }

    private fun textListener(tail: OutputTail): ProcessListener = object : ProcessListener {
        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            if (ProcessOutputType.isStdout(outputType) || ProcessOutputType.isStderr(outputType)) tail.text(event.text)
        }
    }

    private companion object {
        const val MILLIS = 1000L
        const val START_TIMEOUT_MILLIS = 600_000L
    }
}
