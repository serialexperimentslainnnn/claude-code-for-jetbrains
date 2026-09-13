package dev.lain.claudejb.controller.mcp.tools.run

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import dev.lain.claudejb.model.mcp.Batch
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class RunTools(private val project: Project, scope: CoroutineScope) {

    private val jobs = Jobs<Int>(scope, "run")
    private val processRun = ProcessRun(project)

    fun domain(): ToolDomain = ToolDomain(
        "run",
        "Run configurations as the Run tool window sees them: list them, start one and read its console, stop a process",
        listOf(
            Tool(RUN_CONFIGURATIONS, ::runConfigurations),
            Tool(RUN_CONFIGURATION) { args -> ToolResult.toon(Batch.run(args, Batch.RUN_NAMES, runner(args))) },
            Tool(PROCESSES, ::processes),
        ),
    )

    private fun runner(args: ToolArgs): suspend (ToolArgs) -> JsonObject {
        val deadline = Jobs.deadline(args)
        return { runOne(it, deadline) }
    }

    private suspend fun runConfigurations(args: ToolArgs): ToolResult {
        val max = args.int("max", DEFAULT_MAX)
        val (rows, total) = readAction {
            val manager = RunManager.getInstance(project)
            val selected = manager.selectedConfiguration?.uniqueID
            val all = manager.allSettings
            all.take(max).map { settings -> configurationRow(settings, settings.uniqueID == selected) } to all.size
        }
        return ToolResult.toon(
            buildJsonObject {
                put("count", total)
                put("truncated", total > rows.size)
                put("configurations", buildJsonArray { rows.forEach { add(it) } })
            },
        )
    }

    private fun configurationRow(settings: RunnerAndConfigurationSettings, selected: Boolean): JsonObject = buildJsonObject {
        put("name", settings.name)
        put("type", settings.type.displayName)
        put("folder", settings.folderName ?: "")
        put("temporary", settings.isTemporary)
        put("selected", selected)
    }

    private suspend fun runOne(args: ToolArgs, deadline: Deadline): JsonObject {
        val tailLines = OutputTail.lines(args)
        val job = args.optionalString("job")?.let { jobs.find(it) } ?: start(args, deadline)
        val exitCode = jobs.await(job, deadline.remaining())
        return buildJsonObject {
            put("name", args.optionalString("name") ?: "")
            outcome(Jobs.status(exitCode), job.id, exitCode, job.tail, tailLines)
        }
    }

    private suspend fun start(args: ToolArgs, deadline: Deadline): Job<Int> {
        val name = args.string("name")
        if (deadline.expired()) throw ToolException(Jobs.NOT_STARTED)
        val settings = readAction { RunManager.getInstance(project).findConfigurationByName(name) }
            ?: throw ToolException("no run configuration named $name; run_configurations lists them")
        val executor = executor(args)
        val tail = OutputTail.toCard(project, args)
        return jobs.start(tail) { processRun.run(settings, tail, executor) }
    }

    private fun executor(args: ToolArgs): String = EXECUTORS[args.optionalString("executor") ?: "run"]
        ?: throw ToolException("executor must be one of ${EXECUTORS.keys.joinToString()}")

    private suspend fun processes(args: ToolArgs): ToolResult = when (val action = args.optionalString("action") ?: "list") {
        "list" -> list(args.int("max", DEFAULT_MAX))
        "stop" -> stop(args.string("name"))
        else -> throw ToolException("action must be list or stop, not $action")
    }

    private suspend fun list(max: Int): ToolResult {
        val (rows, total) = withContext(Dispatchers.EDT) {
            val all = RunContentManager.getInstance(project).allDescriptors
            all.take(max).map(::processRow) to all.size
        }
        return ToolResult.toon(
            buildJsonObject {
                put("count", total)
                put("truncated", total > rows.size)
                put("processes", buildJsonArray { rows.forEach { add(it) } })
            },
        )
    }

    private fun processRow(descriptor: RunContentDescriptor): JsonObject = buildJsonObject {
        val handler = descriptor.processHandler
        put("name", descriptor.displayName ?: "")
        put("running", handler != null && !handler.isProcessTerminated)
        put("execution_id", descriptor.executionId)
    }

    private suspend fun stop(name: String): ToolResult {
        val stopped = withContext(Dispatchers.EDT) {
            val handler = RunContentManager.getInstance(project).allDescriptors.firstOrNull { it.displayName == name }?.processHandler
                ?: throw ToolException("no process named $name in the Run tool window; processes(action=list) names them")
            val running = !handler.isProcessTerminated
            if (running) handler.destroyProcess()
            running
        }
        return ToolResult.toon(
            buildJsonObject {
                put("name", name)
                put("stopped", stopped)
            },
        )
    }

    companion object {

        private const val DEFAULT_MAX = 50

        val EXECUTORS: Map<String, String> =
            linkedMapOf("run" to "Run", "debug" to "Debug", "coverage" to "Coverage", "profile" to "Profiler")

        val RUN_CONFIGURATIONS = ToolSpec(
            "run_configurations",
            "Lists the project's run configurations as the Run/Debug combo shows them: name, type, folder, whether temporary " +
                "and which one is selected.",
            listOf(Param("max", "Maximum configurations to return (default $DEFAULT_MAX)", type = "integer", required = false)),
        )

        val RUN_CONFIGURATION = ToolSpec(
            "run_configuration",
            "Starts a run configuration exactly as the Run button does, before-launch tasks included, and returns its exit code " +
                "and the end of its console; several in a row with names. executor picks the button: run (default), debug, " +
                "coverage (the Coverage window afterwards) or profile (the Profiler, where installed). Output streams to the " +
                "chat while it runs; status running means call again with job.",
            listOf(
                Param("name", "The configuration name as run_configurations lists it (not needed with job)", required = false),
                Param("executor", "run (default), debug, coverage or profile", required = false),
                Batch.param(Batch.RUN_NAMES, "Several configurations, run one after another within one wait, one result each"),
                Jobs.WAIT,
                OutputTail.TAIL,
                Jobs.JOB,
            ),
            mutates = true,
        )

        val PROCESSES = ToolSpec(
            "processes",
            "The Run tool window's tabs: list them with whether each process is still running, or stop one by name.",
            listOf(
                Param("action", "list (default) or stop", required = false),
                Param("name", "The tab name to stop, as list shows it", required = false),
                Param("max", "Maximum processes to list (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
            mutates = true,
        )
    }
}
