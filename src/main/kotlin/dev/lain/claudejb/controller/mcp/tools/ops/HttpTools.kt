package dev.lain.claudejb.controller.mcp.tools.ops

import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.lain.claudejb.controller.mcp.Reveal
import dev.lain.claudejb.controller.mcp.tools.code.Locations
import dev.lain.claudejb.controller.mcp.tools.run.Deadline
import dev.lain.claudejb.controller.mcp.tools.run.Job
import dev.lain.claudejb.controller.mcp.tools.run.Jobs
import dev.lain.claudejb.controller.mcp.tools.run.OutputTail
import dev.lain.claudejb.controller.mcp.tools.run.ProcessRun
import dev.lain.claudejb.controller.mcp.tools.run.outcome
import dev.lain.claudejb.model.mcp.Batch
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class HttpTools(private val project: Project, scope: CoroutineScope, private val reveal: Reveal) {

    private val jobs = Jobs<Int>(scope, "http")
    private val processRun = ProcessRun(project)

    fun available(): Boolean = HttpClientGateway.available()

    fun domain(): ToolDomain = ToolDomain(
        "http",
        "The IDE's HTTP Client: the project's .http and .rest request files, running one through its run configuration with " +
            "the response console, and opening one in the editor",
        listOf(
            Tool(HTTP_FILES, ::files),
            Tool(HTTP_RUN) { args -> ToolResult.toon(Batch.run(args, Batch.RUN_PATHS, runner(args))) },
            Tool(HTTP_OPEN, ::open),
        ),
    )

    private fun runner(args: ToolArgs): suspend (ToolArgs) -> JsonObject {
        val deadline = Jobs.deadline(args)
        return { runOne(it, deadline) }
    }

    private suspend fun files(args: ToolArgs): ToolResult {
        val max = args.int("max", DEFAULT_MAX)
        val paths = smartReadAction(project) {
            val scope = GlobalSearchScope.projectScope(project)
            EXTENSIONS.flatMap { FilenameIndex.getAllFilesByExt(project, it, scope) }.map { Locations.relative(project, it) }.sorted()
        }
        return ToolResult.toon(
            buildJsonObject {
                put("count", paths.size)
                put("truncated", paths.size > max)
                put("files", buildJsonArray { paths.take(max).forEach { add(JsonPrimitive(it)) } })
            },
        )
    }

    private suspend fun runOne(args: ToolArgs, deadline: Deadline): JsonObject {
        val tailLines = OutputTail.lines(args)
        val job = args.optionalString("job")?.let { jobs.find(it) } ?: start(args, deadline)
        val exitCode = jobs.await(job, deadline.remaining())
        return buildJsonObject {
            put("path", args.optionalString("path") ?: "")
            outcome(Jobs.status(exitCode), job.id, exitCode, job.tail, tailLines)
        }
    }

    private suspend fun start(args: ToolArgs, deadline: Deadline): Job<Int> {
        val path = requestFile(args)
        if (deadline.expired()) throw ToolException(Jobs.NOT_STARTED)
        val psiFile = readAction { Locations.psiFile(project, path) }
        val settings = HttpClientGateway.configurationFor(project, psiFile, path)
        val tail = OutputTail.toCard(project, args)
        return jobs.start(tail) { processRun.run(settings, tail) }
    }

    private suspend fun open(args: ToolArgs): ToolResult {
        val path = requestFile(args)
        val file = readAction { Locations.file(project, path) }
        val opened = reveal.file(file)
        return ToolResult.toon(
            buildJsonObject {
                put("path", path)
                put("opened", opened)
            },
        )
    }

    private fun requestFile(args: ToolArgs): String {
        val path = args.string("path")
        if (path.substringAfterLast('.', "").lowercase() !in EXTENSIONS) {
            throw ToolException("$path is not an HTTP request file; the HTTP Client runs .http and .rest files, http_files lists them")
        }
        return path
    }

    companion object {

        private const val DEFAULT_MAX = 100
        private val EXTENSIONS = listOf("http", "rest")

        val HTTP_FILES = ToolSpec(
            "http_files",
            "Lists the project's HTTP Client request files (.http and .rest) as the IDE indexes them, relative to the project root.",
            listOf(Param("max", "Maximum paths to return (default $DEFAULT_MAX)", type = "integer", required = false)),
        )

        val HTTP_RUN = ToolSpec(
            "http_run",
            "Runs every request of an .http or .rest file through the IDE's HTTP Client run configuration, creating or reusing " +
                "the one the IDE would make from the file, and returns the end of its console; several files in a row with " +
                "paths. Output streams to the chat while it runs; status running means call again with job.",
            listOf(
                Param("path", "The request file, absolute or relative to the project root (not needed with job)", required = false),
                Batch.param(Batch.RUN_PATHS, "Several request files, run one after another within one wait, one result each"),
                Jobs.WAIT,
                OutputTail.TAIL,
                Jobs.JOB,
            ),
            mutates = true,
        )

        val HTTP_OPEN = ToolSpec(
            "http_open",
            "Opens an .http or .rest file in the editor, where the HTTP Client shows a run icon beside each request.",
            listOf(Param("path", "The request file, absolute or relative to the project root")),
        )
    }
}
