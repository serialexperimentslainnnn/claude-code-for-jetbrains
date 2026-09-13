package dev.lain.claudejb.controller.mcp.tools.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import dev.lain.claudejb.controller.mcp.FocusKeeper
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

internal class TerminalTools(private val project: Project, scope: CoroutineScope) {

    private val jobs = Jobs<Int>(scope, "shell")

    fun domain(): ToolDomain = ToolDomain(
        "terminal",
        "A command in the IDE's Terminal tool window, with its console and exit code, and the window's tabs",
        listOf(Tool(SHELL, ::shell), Tool(TERMINAL_TABS, ::tabs)),
    )

    private suspend fun tabs(args: ToolArgs): ToolResult {
        val action = args.optionalString("action") ?: "list"
        val name = closing(action, args.optionalString("name"))
        val rows = withContext(Dispatchers.EDT) {
            val manager = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_WINDOW)?.contentManager
                ?: throw ToolException("this IDE has no Terminal tool window")
            if (action == "close") manager.removeContent(tabNamed(manager, name.orEmpty()), true)
            manager.contents.map { content ->
                buildJsonObject {
                    put("name", content.displayName ?: "")
                    put("selected", manager.isSelected(content))
                    put("ours", content.getUserData(TAB) != null)
                    put("running", content.getUserData(TAB)?.handler?.isProcessTerminated == false)
                }
            }
        }
        return ToolResult.toon(
            buildJsonObject {
                put("action", action)
                put("count", rows.size)
                put("tabs", buildJsonArray { rows.forEach { add(it) } })
            },
        )
    }

    private fun closing(action: String, name: String?): String? = when {
        action != "list" && action != "close" -> throw ToolException("action must be list or close")
        action == "close" && name == null -> throw ToolException("action=close needs name")
        else -> name
    }

    private fun tabNamed(manager: ContentManager, name: String): Content = manager.contents.firstOrNull { it.displayName == name }
        ?: throw ToolException("no terminal tab named $name; action=list names them")

    private suspend fun shell(args: ToolArgs): ToolResult {
        val tailLines = OutputTail.lines(args)
        val job = args.optionalString("job")?.let { jobs.find(it) } ?: start(args)
        val exitCode = jobs.await(job, Jobs.waitMillis(args))
        return ToolResult.toon(buildJsonObject { outcome(Jobs.status(exitCode), job.id, exitCode, job.tail, tailLines) })
    }

    private suspend fun start(args: ToolArgs): Job<Int> {
        val command = args.string("command")
        val directory = readAction {
            args.optionalString("cwd")?.let { ReadTools.resolveDirectory(project, it).path } ?: project.basePath
        } ?: throw ToolException("this project has no directory on disk; pass cwd")
        val handler = handler(command, directory)
        val tail = OutputTail.toCard(project, args)
        return jobs.start(tail) { run(handler, command, tail) }
    }

    private fun handler(command: String, directory: String): ColoredProcessHandler {
        val words = if (SystemInfo.isWindows) {
            listOf("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command)
        } else {
            listOf(System.getenv("SHELL") ?: "/bin/bash", "-c", command)
        }
        val commandLine = GeneralCommandLine(words).withWorkingDirectory(Path.of(directory))
        return try {
            object : ColoredProcessHandler(commandLine) {
                override fun getCommandLineForLog(): String? = null
            }
        } catch (e: ExecutionException) {
            throw ToolException("the shell could not start: ${e.message}", e)
        }
    }

    private suspend fun run(handler: ColoredProcessHandler, command: String, tail: OutputTail): Int {
        val exited = CompletableDeferred<Int>()
        handler.addProcessListener(
            object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    if (ProcessOutputType.isStdout(outputType) || ProcessOutputType.isStderr(outputType)) tail.text(event.text)
                }

                override fun processTerminated(event: ProcessEvent) {
                    exited.complete(event.exitCode)
                }
            },
        )
        FocusKeeper.keep(project) { show(handler) }
        handler.notifyTextAvailable("$ $command\n", ProcessOutputType.SYSTEM)
        handler.startNotify()
        return exited.await()
    }

    private class Tab(val console: ConsoleView, @Volatile var handler: ProcessHandler)

    private fun show(handler: ProcessHandler) {
        val window = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_WINDOW) ?: return
        val manager = window.contentManager
        val content = manager.contents.firstOrNull { it.getUserData(TAB)?.handler?.isProcessTerminated == true }
            ?.also { reuse(it, handler) }
            ?: open(window, handler)
        val userIsThere = window.isActive
        window.show { if (!userIsThere) manager.setSelectedContent(content, false) }
    }

    private fun reuse(content: Content, handler: ProcessHandler) {
        val tab = content.getUserData(TAB) ?: return
        tab.handler = handler
        tab.console.print("\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        tab.console.attachToProcess(handler)
    }

    private fun open(window: ToolWindow, handler: ProcessHandler): Content {
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        console.attachToProcess(handler)
        val tab = Tab(console, handler)
        val content = ContentFactory.getInstance().createContent(console.component, TAB_TITLE, false)
        content.putUserData(TAB, tab)
        content.setDisposer(console)
        Disposer.register(content) { tab.handler.destroyProcess() }
        window.contentManager.addContent(content)
        return content
    }

    companion object {

        private const val TERMINAL_WINDOW = "Terminal"
        private const val TAB_TITLE = "Claude"
        private val TAB: Key<Tab> = Key.create("claude.terminal.tab")

        val TERMINAL_TABS = ToolSpec(
            "terminal_tabs",
            "The tabs of the Terminal tool window: name, whether selected, whether it is Claude's and whether a command " +
                "still runs in it (action=list, the default); action=close closes a tab by name. The user's tab is never " +
                "selected or focused.",
            listOf(
                Param("action", "list (default) or close", required = false),
                Param("name", "The tab name to close", required = false),
            ),
            mutates = true,
        )

        val SHELL = ToolSpec(
            "shell",
            "Runs a command line in the user's shell (bash or the login shell; PowerShell on Windows) inside a tab of the " +
                "IDE's Terminal tool window, and returns its exit code and the end of its output. This replaces Bash. " +
                "The tab is shown without taking the focus, and never switched while the user is in the Terminal. " +
                "Output streams to the chat while it runs; status running means call again with job.",
            listOf(
                Param("command", "The command line, as typed at the prompt (not needed with job)", required = false),
                Param("cwd", "Working directory, relative to the project root (default: the project root)", required = false),
                Jobs.WAIT,
                OutputTail.TAIL,
                Jobs.JOB,
            ),
            mutates = true,
        )
    }
}
