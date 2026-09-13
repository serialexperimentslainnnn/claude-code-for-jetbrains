package dev.lain.claudejb.controller.mcp.tools.ops

import com.intellij.openapi.application.EDT
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import dev.lain.claudejb.controller.mcp.FocusKeeper
import dev.lain.claudejb.controller.mcp.IdeActions
import dev.lain.claudejb.controller.mcp.TargetContext
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class IdeTools(private val project: Project, private val actions: IdeActions, private val scope: CoroutineScope) {

    fun domain(): ToolDomain = ToolDomain(
        "ide",
        "The IDE itself: dispatch any registered action, open or close a tool window, open Settings at a page, list the plugins",
        listOf(
            Tool(IDE_ACTION, ::ideAction),
            Tool(TOOL_WINDOW, ::toolWindow),
            Tool(SETTINGS_OPEN, ::settingsOpen),
            Tool(PLUGINS, ::plugins),
        ),
    )

    private suspend fun ideAction(args: ToolArgs): ToolResult {
        val id = args.string("action_id")
        val target = TargetContext.target(args)
        if (target.named) actions.dispatch(id, target) else actions.dispatch(id)
        return ToolResult.toon(
            buildJsonObject {
                put("action_id", id)
                put("target", target.path ?: target.hash ?: target.node ?: "")
                put("dispatched", true)
            },
        )
    }

    private suspend fun toolWindow(args: ToolArgs): ToolResult {
        val action = args.string("action")
        val id = args.optionalString("id").orEmpty()
        val max = args.int("max", DEFAULT_MAX)
        if (action !in TOOL_WINDOW_ACTIONS) throw ToolException("action must be ${TOOL_WINDOW_ACTIONS.joinToString()}")
        if (action != "list" && id.isEmpty()) throw ToolException("action=$action needs id; action=list shows the ids")
        val rows = withContext(Dispatchers.EDT) {
            val manager = ToolWindowManager.getInstance(project)
            val windows = if (action == "list") {
                manager.toolWindowIds.sorted().mapNotNull(manager::getToolWindow)
            } else {
                val window = window(manager, id)
                if (action == "open") FocusKeeper.keeping(project) { window.activate(null, false) } else window.hide()
                listOf(window)
            }
            windows.map(::row)
        }
        return ToolResult.toon(
            buildJsonObject {
                put("action", action)
                put("id", id)
                put("count", rows.size)
                put("truncated", rows.size > max)
                put("windows", buildJsonArray { rows.take(max).forEach { add(it) } })
            },
        )
    }

    private fun window(manager: ToolWindowManager, id: String): ToolWindow {
        val ids = manager.toolWindowIds
        val exact = ids.firstOrNull { it == id } ?: ids.firstOrNull { it.equals(id, ignoreCase = true) }
        return exact?.let(manager::getToolWindow)
            ?: throw ToolException("this IDE has no tool window $id; the ids are ${ids.sorted().joinToString()}")
    }

    private fun row(window: ToolWindow): JsonObject = buildJsonObject {
        put("id", window.id)
        put("title", window.stripeTitle)
        put("visible", window.isVisible)
        put("active", window.isActive)
    }

    private suspend fun settingsOpen(args: ToolArgs): ToolResult {
        val name = args.optionalString("name").orEmpty()
        scope.launch(Dispatchers.EDT) {
            val util = ShowSettingsUtil.getInstance()
            if (name.isEmpty()) util.showSettingsDialog(project, null as Configurable?) else util.showSettingsDialog(project, name)
        }
        return ToolResult.toon(
            buildJsonObject {
                put("name", name)
                put("opened", true)
            },
        )
    }

    private suspend fun plugins(args: ToolArgs): ToolResult {
        val max = args.int("max", DEFAULT_MAX)
        val filter = args.optionalString("filter").orEmpty()
        val matching = PluginDetailsGateway.activePlugins()
            .filter { filter.isEmpty() || matches(it, filter) }
            .sortedBy { it.id }
        return ToolResult.toon(
            buildJsonObject {
                put("filter", filter)
                put("count", matching.size)
                put("truncated", matching.size > max)
                put("plugins", buildJsonArray { matching.take(max).forEach { add(pluginRow(it)) } })
            },
        )
    }

    private fun matches(plugin: PluginDetailsGateway.Row, filter: String): Boolean =
        plugin.id.contains(filter, ignoreCase = true) || plugin.name.contains(filter, ignoreCase = true)

    private fun pluginRow(plugin: PluginDetailsGateway.Row): JsonObject = buildJsonObject {
        put("id", plugin.id)
        put("name", plugin.name)
        put("version", plugin.version)
        put("vendor", plugin.vendor)
        put("enabled", plugin.enabled)
    }

    companion object {

        private const val DEFAULT_MAX = 100

        val TOOL_WINDOW_ACTIONS: List<String> = listOf("open", "close", "list")

        val IDE_ACTION = ToolSpec(
            "ide_action",
            "Performs one registered IDE action by its id, exactly as its menu entry or shortcut would, with the project and " +
                "the repository root as context, or with a target: a file (path, line, column: the action sees the file, " +
                "its PSI and an editor on it, as the editor's context menu would), a commit (hash: selected in the Git Log, " +
                "as the log's context menu would) or a Services node (node). Use it for what no other tool covers; actions " +
                "lists the ids. It returns as soon as the action is dispatched, and an action that opens a dialog leaves " +
                "it for the user. Refused when the IDE has no such action or it is not enabled in this context.",
            listOf(Param("action_id", "The action id as registered in the IDE, e.g. ShowSettings, Synchronize, CloseAllEditors")) +
                TargetContext.PARAMS,
            mutates = true,
        )

        val TOOL_WINDOW = ToolSpec(
            "tool_window",
            "Opens or closes one of the IDE's tool windows, or lists them all with whether each is visible and active. " +
                "Use action=open to put a view in front of the user (Services, Problems View, Version Control, Run, Debug, " +
                "Project, Structure, Database) without taking the focus, and action=list when unsure of an id.",
            listOf(
                Param("action", "open, close or list"),
                Param("id", "The tool window id, matched case-insensitively (action=open and close)", required = false),
                Param("max", "Maximum windows to list (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
            mutates = true,
        )

        val SETTINGS_OPEN = ToolSpec(
            "settings_open",
            "Opens the IDE's Settings, at the page whose display name matches when name is given (Editor, Plugins, Keymap, " +
                "Appearance, Claude Code). An unknown name opens Settings at its default page. Returns as soon as the dialog " +
                "is requested; the user applies or cancels it.",
            listOf(Param("name", "Display name of the settings page to select (default: none)", required = false)),
            mutates = true,
        )

        val PLUGINS = ToolSpec(
            "plugins",
            "Lists the plugins loaded in this IDE, with id, name, version, vendor and whether each is enabled. Use it before " +
                "relying on a plugin's tool window, action or file type; filter by a fragment of the id or the name. " +
                "Refused on IDEs older than 2026.2, which have no public plugin listing.",
            listOf(
                Param("filter", "Only plugins whose id or name contains this text, case-insensitive", required = false),
                Param("max", "Maximum plugins to return (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )
    }
}
