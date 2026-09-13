package dev.lain.claudejb.controller.mcp.tools.run

import com.intellij.build.BuildProgressListener
import com.intellij.build.BuildViewManager
import com.intellij.build.FileNavigatable
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.FileMessageEvent
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.OutputBuildEvent
import com.intellij.build.events.StartBuildEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.task.ProjectTaskListener
import com.intellij.task.ProjectTaskManager
import dev.lain.claudejb.controller.mcp.tools.code.ReadTools
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal class BuildOutcome(
    val aborted: Boolean,
    val hasErrors: Boolean,
    val errors: List<JsonObject>,
    val errorsCount: Int,
    val warnings: Int,
)

internal class BuildTools(private val project: Project, scope: CoroutineScope) {

    private val jobs = Jobs<BuildOutcome>(scope, "build")

    fun domain(): ToolDomain = ToolDomain(
        "build",
        "The IDE's build: all modules, incremental or from scratch, with the compiler's errors and their positions",
        listOf(Tool(BUILD, ::build)),
    )

    private suspend fun build(args: ToolArgs): ToolResult {
        val kind = args.optionalString("kind") ?: "build"
        if (kind !in KINDS) throw ToolException("kind must be one of ${KINDS.joinToString()}")
        val max = args.int("max", DEFAULT_MAX)
        val tailLines = OutputTail.lines(args)
        val scope = scope(kind, args)
        val job = args.optionalString("job")?.let { jobs.find(it) }
            ?: OutputTail.toCard(project, args).let { tail -> jobs.start(tail) { run(kind, scope, tail) } }
        val outcome = jobs.await(job, Jobs.waitMillis(args))
        return ToolResult.toon(
            buildJsonObject {
                put("kind", kind)
                put("status", Jobs.status(outcome))
                put("job", job.id)
                put("aborted", outcome?.aborted ?: false)
                put("has_errors", outcome?.hasErrors ?: false)
                put("errors_count", outcome?.errorsCount ?: 0)
                put("warnings_count", outcome?.warnings ?: 0)
                val shown = outcome?.errors.orEmpty().take(max)
                put("truncated", (outcome?.errorsCount ?: 0) > shown.size)
                put("errors", buildJsonArray { shown.forEach { add(it) } })
                tailOf(job.tail, tailLines)
            },
        )
    }

    private suspend fun scope(kind: String, args: ToolArgs): (ProjectTaskManager) -> Unit = when (kind) {
        "module" -> {
            val name = args.string("module")
            val module = ModuleManager.getInstance(project).findModuleByName(name)
                ?: throw ToolException("no module named $name; modules lists them")
            ({ it.build(module) })
        }

        "file" -> {
            val file = readAction { ReadTools.resolveFile(project, args.string("path")) }
            ({ it.compile(file) })
        }

        "rebuild" -> ({ it.rebuildAllModules() })

        else -> ({ it.buildAllModules() })
    }

    private suspend fun run(kind: String, scope: (ProjectTaskManager) -> Unit, tail: OutputTail): BuildOutcome {
        val finished = CompletableDeferred<ProjectTaskManager.Result>()
        val events = BuildEvents(project, tail)
        val disposable = Disposer.newDisposable("Claude build")
        try {
            project.messageBus.connect(disposable).subscribe(
                ProjectTaskListener.TOPIC,
                object : ProjectTaskListener {
                    override fun finished(result: ProjectTaskManager.Result) {
                        finished.complete(result)
                    }
                },
            )
            project.service<BuildViewManager>().addListener(events, disposable)
            tail.text("$ build $kind\n")
            withContext(Dispatchers.EDT) { scope(ProjectTaskManager.getInstance(project)) }
            val result = finished.await()
            return BuildOutcome(
                aborted = result.isAborted,
                hasErrors = result.hasErrors(),
                errors = events.errors.toList(),
                errorsCount = events.errorsCount.get(),
                warnings = events.warnings.get(),
            )
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private class BuildEvents(private val project: Project, private val tail: OutputTail) : BuildProgressListener {

        @Volatile
        private var buildId: Any? = null
        val errors = CopyOnWriteArrayList<JsonObject>()
        val errorsCount = AtomicInteger()
        val warnings = AtomicInteger()

        override fun onEvent(buildId: Any, event: BuildEvent) {
            if (event is StartBuildEvent && this.buildId == null) this.buildId = buildId
            if (buildId != this.buildId) return
            when (event) {
                is OutputBuildEvent -> tail.text(event.message)
                is MessageEvent -> message(event)
                else -> Unit
            }
        }

        private fun message(event: MessageEvent) {
            when (event.kind) {
                MessageEvent.Kind.ERROR -> {
                    if (errorsCount.getAndIncrement() < MAX_STORED) errors += row(event)
                    tail.line("error: ${event.message}")
                }

                MessageEvent.Kind.WARNING -> warnings.incrementAndGet()

                else -> Unit
            }
        }

        private fun row(event: MessageEvent): JsonObject = buildJsonObject {
            val position = (event as? FileMessageEvent)?.filePosition
            put("file", fileOf(event)?.let(::relative) ?: "")
            put("line", position?.startLine?.plus(1) ?: 0)
            put("column", position?.startColumn?.plus(1) ?: 0)
            put("message", event.message)
        }

        private fun fileOf(event: MessageEvent): String? = when (val target = event.getNavigatable(project)) {
            is OpenFileDescriptor -> target.file.path
            is FileNavigatable -> target.fileDescriptor?.file?.path
            else -> null
        }

        private fun relative(path: String): String {
            val base = project.basePath ?: return path
            return path.removePrefix("$base/")
        }
    }

    companion object {

        private const val DEFAULT_MAX = 50
        private const val MAX_STORED = 200

        val KINDS: List<String> = listOf("build", "rebuild", "module", "file")

        val BUILD = ToolSpec(
            "build",
            "Builds as the Build menu does and reports the compiler's errors with file, line and column: the whole project " +
                "(kind=build, incremental, or rebuild from scratch), one module (kind=module with module) or one file " +
                "(kind=file with path, as Recompile does). Output streams to the chat while it runs; answer status running " +
                "means it is still going, call again with job.",
            listOf(
                Param("kind", "build (default), rebuild, module or file", required = false),
                Param("module", "The module name (kind=module)", required = false),
                Param("path", "The file to recompile, relative to the project root (kind=file)", required = false),
                Jobs.WAIT,
                OutputTail.TAIL,
                Param("max", "Maximum errors to return (default $DEFAULT_MAX)", type = "integer", required = false),
                Jobs.JOB,
            ),
            mutates = true,
        )
    }
}
