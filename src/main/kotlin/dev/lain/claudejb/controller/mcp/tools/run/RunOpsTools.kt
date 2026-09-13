package dev.lain.claudejb.controller.mcp.tools.run

import com.intellij.execution.RunManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import dev.lain.claudejb.controller.mcp.IdeActions
import dev.lain.claudejb.controller.mcp.Reveal
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

internal class RunOpsTools(private val project: Project, private val actions: IdeActions, private val reveal: Reveal) {

    fun domain(): ToolDomain = ToolDomain(
        "run_ops",
        "The Run menu beyond starting: the run configuration editor, attaching the debugger to a process, and the " +
            "coverage overlay and reports",
        listOf(Tool(EDIT_CONFIGURATION, ::editConfiguration), Tool(ATTACH, ::attach), Tool(COVERAGE, ::coverage)),
    )

    private suspend fun editConfiguration(args: ToolArgs): ToolResult {
        val name = args.optionalString("name")
        if (name != null) {
            withContext(Dispatchers.EDT) {
                val manager = RunManager.getInstance(project)
                manager.selectedConfiguration = manager.findConfigurationByName(name)
                    ?: throw ToolException("no run configuration named $name; run_configurations lists them")
            }
        }
        actions.dispatch(EDIT_CONFIGURATIONS)
        return ToolResult.toon(
            buildJsonObject {
                put("name", name ?: "")
                put("dispatched", true)
            },
        )
    }

    private suspend fun attach(ignored: ToolArgs): ToolResult {
        actions.dispatch(ATTACH_TO_PROCESS)
        return ToolResult.toon(buildJsonObject { put("dispatched", true) })
    }

    private suspend fun coverage(args: ToolArgs): ToolResult {
        val action = args.optionalString("action") ?: "show"
        val id = COVERAGE_ACTIONS[action] ?: throw ToolException("action must be one of ${COVERAGE_ACTIONS.keys.joinToString()}")
        if (id.isNotEmpty()) actions.dispatch(id)
        val shown = reveal.toolWindow(COVERAGE_WINDOW)
        return ToolResult.toon(
            buildJsonObject {
                put("action", action)
                put("id", id)
                put("window", shown)
            },
        )
    }

    companion object {

        private const val EDIT_CONFIGURATIONS = "editRunConfigurations"
        private const val ATTACH_TO_PROCESS = "XDebugger.AttachToProcess"
        private const val COVERAGE_WINDOW = "Coverage"

        val COVERAGE_ACTIONS: Map<String, String> = linkedMapOf(
            "show" to "",
            "switch" to "SwitchCoverage",
            "hide" to "HideCoverage",
            "report" to "GenerateCoverageReport",
            "import" to "ImportCoverage",
        )

        val EDIT_CONFIGURATION = ToolSpec(
            "edit_configuration",
            "Opens Run ▸ Edit Configurations, at the named configuration when name is given, for the user to change it; " +
                "a configuration a task lacks is created there or by hand under .idea/runConfigurations.",
            listOf(Param("name", "The configuration to select (default: the selected one)", required = false)),
            mutates = true,
        )

        val ATTACH = ToolSpec(
            "attach",
            "Run ▸ Attach to Process: the IDE's chooser of running processes opens for the user to pick one and debug it.",
            emptyList(),
            mutates = true,
        )

        val COVERAGE = ToolSpec(
            "coverage",
            "The Coverage tool window and its actions: show (default) reveals the window with the current suite, switch " +
                "chooses a suite, hide clears the editor overlay, report generates the HTML report, import loads a report; " +
                "run a configuration with executor=coverage first.",
            listOf(Param("action", "show (default), switch, hide, report or import", required = false)),
            mutates = true,
        )
    }
}
