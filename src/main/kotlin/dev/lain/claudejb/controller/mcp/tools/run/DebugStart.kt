package dev.lain.claudejb.controller.mcp.tools.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class DebugStart(private val project: Project, private val sessions: DebugSessions) {

    suspend fun start(args: ToolArgs): XDebugSession {
        val name = args.string("name")
        val wait = DebugSpecs.waitSeconds(args, DebugSpecs.DEFAULT_START_WAIT)
        val settings = readAction { RunManager.getInstance(project).findConfigurationByName(name) }
            ?: throw ToolException("no run configuration named $name; run_configurations lists them")
        val started = CompletableDeferred<XDebugSession>()
        val connection = project.messageBus.connect()
        try {
            connection.subscribe(XDebuggerManager.TOPIC, opened(settings, started))
            connection.subscribe(ExecutionManager.EXECUTION_TOPIC, refused(settings, started))
            launch(settings)
            val session = withTimeoutOrNull(wait * MILLIS) { started.await() }
                ?: throw ToolException("$name did not open a debug session within $wait s; the Run tool window shows why")
            sessions.awaitPause(session, wait)
            return session
        } finally {
            connection.disconnect()
        }
    }

    private fun opened(settings: RunnerAndConfigurationSettings, started: CompletableDeferred<XDebugSession>) =
        object : XDebuggerManagerListener {
            override fun processStarted(debugProcess: XDebugProcess) {
                val session = debugProcess.session
                if (session.executionEnvironment?.runnerAndConfigurationSettings?.uniqueID == settings.uniqueID) started.complete(session)
            }
        }

    private fun refused(settings: RunnerAndConfigurationSettings, started: CompletableDeferred<XDebugSession>) =
        object : ExecutionListener {
            override fun processNotStarted(executorId: String, env: ExecutionEnvironment, cause: Throwable?) {
                if (env.runnerAndConfigurationSettings?.uniqueID == settings.uniqueID) {
                    started.completeExceptionally(
                        ToolException("${settings.name} did not start: ${cause?.message ?: "the IDE refused to run it"}"),
                    )
                }
            }
        }

    private suspend fun launch(settings: RunnerAndConfigurationSettings) = withContext(Dispatchers.EDT) {
        val environment = try {
            ExecutionEnvironmentBuilder.create(DefaultDebugExecutor.getDebugExecutorInstance(), settings).activeTarget().build()
        } catch (e: ExecutionException) {
            throw ToolException("${settings.name} cannot be debugged: ${e.message}", e)
        }
        ExecutionManager.getInstance(project).restartRunProfile(environment)
    }

    private companion object {
        const val MILLIS = 1000L
    }
}
