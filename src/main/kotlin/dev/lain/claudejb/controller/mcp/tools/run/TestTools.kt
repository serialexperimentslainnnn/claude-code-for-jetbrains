package dev.lain.claudejb.controller.mcp.tools.run

import com.intellij.execution.PsiLocation
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testIntegration.TestFramework
import dev.lain.claudejb.controller.mcp.tools.code.Locations
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class TestTools(private val project: Project, scope: CoroutineScope) {

    private val jobs = Jobs<TestOutcome>(scope, "tests")
    private val processRun = ProcessRun(project)

    fun domain(): ToolDomain = ToolDomain(
        "tests",
        "Tests through the IDE's test runner: run a file, the test at a line or a named configuration, and list the tests " +
            "the IDE recognises in a file",
        listOf(Tool(RUN_TESTS) { args -> ToolResult.toon(Batch.run(args, Batch.RUN_PATHS, runner(args))) }, Tool(TESTS, ::tests)),
    )

    private fun runner(args: ToolArgs): suspend (ToolArgs) -> JsonObject {
        val deadline = Jobs.deadline(args)
        return { runOne(it, deadline) }
    }

    private suspend fun runOne(args: ToolArgs, deadline: Deadline): JsonObject {
        val tailLines = OutputTail.lines(args)
        val max = args.int("max", DEFAULT_MAX)
        val job = args.optionalString("job")?.let { jobs.find(it) } ?: start(args, deadline)
        val outcome = jobs.await(job, deadline.remaining())
        val failures = outcome?.failures.orEmpty()
        return buildJsonObject {
            put("target", args.optionalString("name") ?: args.optionalString("path") ?: "")
            outcome(Jobs.status(outcome), job.id, outcome?.exitCode, job.tail, tailLines)
            put("tracked", outcome?.tracked ?: false)
            put("passed", outcome?.passed ?: 0)
            put("failed", outcome?.failed ?: 0)
            put("ignored", outcome?.ignored ?: 0)
            put("truncated", failures.size > max)
            put(
                "failures",
                buildJsonArray {
                    failures.take(max).forEach { row ->
                        add(
                            buildJsonObject {
                                put("test", row.test)
                                put("message", row.message)
                                put("at", row.at)
                            },
                        )
                    }
                },
            )
        }
    }

    private suspend fun start(args: ToolArgs, deadline: Deadline): Job<TestOutcome> {
        val name = args.optionalString("name")
        val path = args.optionalString("path")
        if (deadline.expired()) throw ToolException(Jobs.NOT_STARTED)
        val settings = when {
            name != null && path == null -> byName(name)
            path != null && name == null -> fromFile(path, args)
            else -> throw ToolException("give exactly one of name (a run configuration) or path (a test file, with an optional line)")
        }
        val tail = OutputTail.toCard(project, args)
        return jobs.start(tail) { run(settings, tail) }
    }

    private suspend fun byName(name: String): RunnerAndConfigurationSettings =
        readAction { RunManager.getInstance(project).findConfigurationByName(name) }
            ?: throw ToolException("no run configuration named $name; run_configurations lists them")

    private suspend fun fromFile(path: String, args: ToolArgs): RunnerAndConfigurationSettings {
        val settings = indexed {
            val psiFile = Locations.psiFile(project, path)
            val element = if (args.int("line", 0) > 0) {
                Locations.locate(project, args).let { it.psiFile.findElementAt(it.offset) }
            } else {
                found(psiFile).firstOrNull { it.kind == "class" }?.element
            }
            val context = ConfigurationContext.createEmptyContextForLocation(PsiLocation(project, element ?: psiFile))
            val offered = context.configurationsFromContext.orEmpty()
            (offered.firstOrNull { it.configurationType.id != GRADLE_TYPE } ?: offered.firstOrNull())?.configurationSettings
        } ?: throw ToolException("the IDE offers no test run for $path; is it a test file, and is its framework's plugin enabled?")
        withContext(Dispatchers.EDT) {
            val manager = RunManager.getInstance(project)
            if (!manager.hasSettings(settings)) manager.setTemporaryConfiguration(settings)
        }
        return settings
    }

    private suspend fun run(settings: RunnerAndConfigurationSettings, tail: OutputTail): TestOutcome {
        val listener = TestRunListener(tail)
        val connection = project.messageBus.connect()
        try {
            connection.subscribe(SMTRunnerEventsListener.TEST_STATUS, listener)
            val exitCode = processRun.run(settings, tail) { listener.handler = it }
            withTimeoutOrNull(FINISH_GRACE_MILLIS) { listener.finished.await() }
            return listener.outcome(exitCode)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun tests(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val max = args.int("max", DEFAULT_MAX)
        val (rows, total) = indexed {
            val psiFile = Locations.psiFile(project, path)
            val document = psiFile.viewProvider.document
            val all = found(psiFile)
            all.take(max).map { found ->
                buildJsonObject {
                    put("name", found.element.name ?: "")
                    put("kind", found.kind)
                    put("line", document?.getLineNumber(found.element.textOffset)?.plus(1) ?: 0)
                    put("framework", found.framework)
                }
            } to all.size
        }
        return ToolResult.toon(
            buildJsonObject {
                put("path", path)
                put("count", total)
                put("truncated", total > rows.size)
                put("tests", buildJsonArray { rows.forEach { add(it) } })
            },
        )
    }

    private class Found(val element: PsiNamedElement, val kind: String, val framework: String)

    private fun found(psiFile: PsiFile): List<Found> {
        val frameworks = TestFramework.EXTENSION_NAME.extensionList
        return PsiTreeUtil.collectElementsOfType(psiFile, PsiNamedElement::class.java).mapNotNull { element ->
            frameworks.firstOrNull { it.isTestMethod(element) }?.let { Found(element, "method", it.name) }
                ?: frameworks.firstOrNull { it.isTestClass(element) && !insideTestClass(element, it) }
                    ?.let { Found(element, "class", it.name) }
        }
    }

    private fun insideTestClass(element: PsiElement, framework: TestFramework): Boolean =
        generateSequence(element.parent) { it.parent }.any { it is PsiNamedElement && framework.isTestClass(it) }

    private suspend fun <T> indexed(body: () -> T): T = try {
        readAction(body)
    } catch (e: IndexNotReadyException) {
        throw ToolException("the IDE is still indexing; retry in a moment", e)
    }

    companion object {

        private const val GRADLE_TYPE = "GradleRunConfiguration"

        private const val DEFAULT_MAX = 50
        private const val FINISH_GRACE_MILLIS = 5_000L

        val RUN_TESTS = ToolSpec(
            "run_tests",
            "Runs tests through the IDE's test runner and returns pass/fail/ignored counts with each failure's message and " +
                "frame: a file (path), several files in a row (paths), the test at a line (path + line) or a named run " +
                "configuration (name). A path runs through the test framework's own runner when the IDE offers one, and " +
                "through Gradle only when nothing else does. Output streams to the chat while it runs; status running means " +
                "call again with job.",
            listOf(
                Param("path", "A test file, absolute or relative to the project root", required = false),
                Batch.param(Batch.RUN_PATHS, "Several test files, run one after another within one wait, one result each"),
                Param(
                    "line",
                    "1-based line of the test to run inside path (default: the file's first test class)",
                    type = "integer",
                    required = false,
                ),
                Param("name", "A run configuration name, instead of path", required = false),
                Jobs.WAIT,
                OutputTail.TAIL,
                Param("max", "Maximum failures to return (default $DEFAULT_MAX)", type = "integer", required = false),
                Jobs.JOB,
            ),
            mutates = true,
        )

        val TESTS = ToolSpec(
            "tests",
            "Lists the test classes and test methods the IDE's test frameworks recognise in one file, with their lines.",
            listOf(
                Param("path", "File path, absolute or relative to the project root"),
                Param("max", "Maximum tests to return (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )
    }
}
