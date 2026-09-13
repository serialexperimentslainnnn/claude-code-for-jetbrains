package dev.lain.claudejb.controller.mcp.tools.ops

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import dev.lain.claudejb.controller.mcp.IdeActions
import dev.lain.claudejb.controller.mcp.TargetContext
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class ConsoleTools(private val project: Project, private val actions: IdeActions) {

    fun domain(): ToolDomain = ToolDomain(
        "consoles",
        "The language consoles and tools of the Tools menu: the Groovy console, the Kotlin bytecode viewer and project " +
            "configuration, the Python console",
        listOf(
            Tool(GROOVY_CONSOLE, ::groovy),
            Tool(KOTLIN_BYTECODE, ::kotlinBytecode),
            Tool(KOTLIN_CONFIGURE, ::kotlinConfigure),
            Tool(PYTHON_CONSOLE, ::python),
        ),
    )

    private suspend fun groovy(ignored: ToolArgs): ToolResult = fire(GROOVY_IDS, null)

    private suspend fun kotlinBytecode(args: ToolArgs): ToolResult = fire(KOTLIN_BYTECODE_IDS, TargetContext.target(args))

    private suspend fun kotlinConfigure(ignored: ToolArgs): ToolResult = fire(KOTLIN_CONFIGURE_IDS, null)

    private suspend fun python(ignored: ToolArgs): ToolResult = fire(PYTHON_IDS, null)

    private suspend fun fire(ids: List<String>, target: TargetContext.Target?): ToolResult {
        val id = withContext(Dispatchers.EDT) { ids.firstOrNull { ActionManager.getInstance().getAction(it) != null } }
            ?: throw ToolException("this IDE registers none of ${ids.joinToString()}; the plugin that provides it is not installed")
        if (target != null && target.named) actions.dispatch(id, target) else actions.dispatch(id)
        return ToolResult.toon(
            buildJsonObject {
                put("id", id)
                put("path", target?.path ?: "")
                put("project", project.name)
                put("dispatched", true)
            },
        )
    }

    companion object {

        val GROOVY_IDS: List<String> = listOf("Groovy.Console")
        val KOTLIN_BYTECODE_IDS: List<String> = listOf("KotlinShowBytecode", "ShowKotlinBytecode", "Kotlin.ShowBytecode")
        val KOTLIN_CONFIGURE_IDS: List<String> = listOf("ConfigureKotlinInProject", "KotlinConfigureUpdates")
        val PYTHON_IDS: List<String> = listOf("PyConsole", "Python.Console", "RunPythonConsole")

        val GROOVY_CONSOLE = ToolSpec(
            "groovy_console",
            "Tools ▸ Groovy Console: opens the IDE's Groovy console in the editor, where the user runs snippets against the " +
                "project's classpath (Groovy plugin).",
            emptyList(),
            mutates = true,
        )

        val KOTLIN_BYTECODE = ToolSpec(
            "kotlin_bytecode",
            "Tools ▸ Kotlin ▸ Show Kotlin Bytecode for a file: the IDE's bytecode panel opens beside the editor, following " +
                "the caret (Kotlin plugin).",
            listOf(
                Param("path", "The Kotlin file, relative to the project root"),
                Param("line", "1-based line to show", type = "integer", required = false),
            ),
            mutates = true,
        )

        val KOTLIN_CONFIGURE = ToolSpec(
            "kotlin_configure",
            "Tools ▸ Kotlin ▸ Configure Kotlin in Project: the IDE's dialog adds the Kotlin plugin to the build (Kotlin plugin).",
            emptyList(),
            mutates = true,
        )

        val PYTHON_CONSOLE = ToolSpec(
            "python_console",
            "Tools ▸ Python or Debug Console: opens the IDE's Python console for the project's interpreter (Python plugin).",
            emptyList(),
            mutates = true,
        )
    }
}
