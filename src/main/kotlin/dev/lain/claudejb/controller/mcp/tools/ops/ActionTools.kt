package dev.lain.claudejb.controller.mcp.tools.ops

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class ActionTools(private val project: Project, private val actions: IdeActions) {

    fun domain(): ToolDomain = ToolDomain(
        "actions",
        "Every action this IDE registers, menus included: find one by id or text, walk the main menu, and flip the View " +
            "menu's appearance and interface switches",
        listOf(Tool(ACTIONS, ::list), Tool(MENU, ::menu), Tool(APPEARANCE, ::appearance), Tool(UI, ::ui)),
    )

    private suspend fun list(args: ToolArgs): ToolResult {
        val query = args.optionalString("query").orEmpty()
        val max = args.int("max", DEFAULT_MAX)
        val rows = withContext(Dispatchers.EDT) {
            val manager = ActionManager.getInstance()
            val context = TargetContext(project).project()
            val all = manager.getActionIdList("")
                .asSequence()
                .mapNotNull { id -> manager.getActionOrStub(id)?.let { id to it } }
                .filter { (id, action) -> matches(query, id, action) }
                .sortedBy { it.first }
                .toList()
            all.take(max).map { (id, action) -> row(id, action, manager.isGroup(id), enabled(action, context)) } to all.size
        }
        return ToolResult.toon(
            buildJsonObject {
                put("query", query)
                put("count", rows.second)
                put("truncated", rows.second > rows.first.size)
                put("actions", buildJsonArray { rows.first.forEach { add(it) } })
            },
        )
    }

    private fun matches(query: String, id: String, action: AnAction): Boolean =
        query.isEmpty() || id.contains(query, true) || plain(action.templatePresentation.text).contains(query, true)

    private fun enabled(action: AnAction, context: DataContext): Boolean {
        val event = AnActionEvent.createEvent(action, context, null, ActionPlaces.MAIN_MENU, ActionUiKind.NONE, null)
        return runCatching { ActionUtil.updateAction(action, event) }.isSuccess && event.presentation.isEnabledAndVisible
    }

    private fun shownText(action: AnAction, context: DataContext): String {
        val template = plain(action.templatePresentation.text)
        if (template.isNotEmpty()) return template
        val event = AnActionEvent.createEvent(action, context, null, ActionPlaces.MAIN_MENU, ActionUiKind.NONE, null)
        runCatching { ActionUtil.updateAction(action, event) }
        return plain(event.presentation.text)
    }

    private fun row(
        id: String,
        action: AnAction,
        group: Boolean,
        enabled: Boolean,
        text: String = plain(action.templatePresentation.text),
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("text", text)
        put("description", action.templatePresentation.description.orEmpty())
        put("group", group)
        put("enabled", enabled)
    }

    private suspend fun menu(args: ToolArgs): ToolResult {
        val path = args.optionalString("path").orEmpty()
        val segments = path.split(MENU_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        val rows = withContext(Dispatchers.EDT) {
            val manager = ActionManager.getInstance()
            var group = manager.getAction(MAIN_MENU) as? DefaultActionGroup ?: throw ToolException("this IDE has no main menu group")
            for ((depth, segment) in segments.withIndex()) {
                group = group.getChildActionsOrStubs()
                    .filterIsInstance<DefaultActionGroup>()
                    .firstOrNull { plain(it.templatePresentation.text).equals(segment, ignoreCase = true) || manager.getId(it) == segment }
                    ?: throw ToolException("no menu $segment under " + parent(segments.take(depth)))
            }
            val context = TargetContext(project).project()
            group.getChildActionsOrStubs().filterNot { it is Separator }.map { child ->
                val id = manager.getId(child).orEmpty()
                row(id, child, child is DefaultActionGroup, enabled = true, text = shownText(child, context))
            }
        }
        return ToolResult.toon(
            buildJsonObject {
                put("path", segments.joinToString(MENU_SEPARATOR))
                put("count", rows.size)
                put("items", buildJsonArray { rows.forEach { add(it) } })
            },
        )
    }

    private suspend fun appearance(args: ToolArgs): ToolResult = flip(args, "mode", APPEARANCE_MODES)

    private suspend fun ui(args: ToolArgs): ToolResult =
        if (args.optionalString("part") == NAVIGATION_BAR) navigationBar(args) else flip(args, "part", UI_PARTS)

    private suspend fun navigationBar(args: ToolArgs): ToolResult {
        val settings = UISettings.getInstance()
        val on = args.optionalBoolean("on") ?: !settings.showNavigationBar
        val id = if (on) NAV_BAR_SHOW else NAV_BAR_HIDE
        withContext(Dispatchers.EDT) { actions.dispatch(id) }
        return ToolResult.toon(
            buildJsonObject {
                put("part", NAVIGATION_BAR)
                put("id", id)
                put("on", on)
            },
        )
    }

    private suspend fun flip(args: ToolArgs, key: String, table: Map<String, String>): ToolResult {
        val name = args.string(key)
        val id = table[name] ?: throw ToolException("$key must be one of ${table.keys.joinToString()}")
        val on = args.optionalBoolean("on")
        val now = actions.toggle(id, on)
        return ToolResult.toon(
            buildJsonObject {
                put(key, name)
                put("id", id)
                put("on", now)
            },
        )
    }

    private fun parent(segments: List<String>): String = segments.ifEmpty { listOf("the main menu") }.joinToString(MENU_SEPARATOR)

    private fun plain(text: String?): String = StringUtil.removeHtmlTags(text.orEmpty()).replace("_", "").removeSuffix("…").trim()

    companion object {

        private const val DEFAULT_MAX = 100
        private const val MAIN_MENU = "MainMenu"
        private const val MENU_SEPARATOR = "/"
        private const val NAVIGATION_BAR = "navigation_bar"
        private const val NAV_BAR_SHOW = "NavBarLocationTop"
        private const val NAV_BAR_HIDE = "NavBarLocationHide"

        val APPEARANCE_MODES: Map<String, String> = linkedMapOf(
            "presentation" to "TogglePresentationMode",
            "distraction_free" to "ToggleDistractionFreeMode",
            "full_screen" to "ToggleFullScreen",
            "zen" to "ToggleZenMode",
            "compact" to "ToggleCompactMode",
            "assistant" to "TogglePresentationAssistantAction",
        )

        val UI_PARTS: Map<String, String> = linkedMapOf(
            "toolbar" to "ViewToolBar",
            NAVIGATION_BAR to NAV_BAR_SHOW,
            "tool_window_bars" to "ViewToolButtons",
            "status_bar" to "ViewStatusBar",
            "main_menu" to "ViewMainMenu",
        )

        val ACTIONS = ToolSpec(
            "actions",
            "Lists the actions this IDE registers, plugins included, with id, menu text, description, whether it is a group " +
                "and whether it is enabled in the project context right now; filter by a fragment of the id or the text. " +
                "Use it to find the id for ide_action when no named tool covers a menu entry.",
            listOf(
                Param("query", "Case-insensitive fragment of the action id or its menu text (default: all)", required = false),
                Param("max", "Maximum actions to return (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )

        val MENU = ToolSpec(
            "menu",
            "Walks the IDE's main menu as the user sees it: the top-level menus with no path, or the items of one menu by " +
                "its path (Code/Analyze, Git/GitHub, View/Tool Windows), each with its action id for ide_action.",
            listOf(Param("path", "Menu path with / between levels, e.g. Tools or Code/Analyze (default: the top level)", required = false)),
        )

        val APPEARANCE = ToolSpec(
            "appearance",
            "Flips one of View ▸ Appearance's modes: presentation, distraction_free, full_screen, zen, compact or the " +
                "Presentation Assistant that shows every action fired on screen. Without on it toggles; with on it sets. " +
                "Returns the state after the call.",
            listOf(
                Param("mode", "presentation, distraction_free, full_screen, zen, compact or assistant"),
                Param("on", "true to turn on, false to turn off (default: toggle)", type = "boolean", required = false),
            ),
            mutates = true,
        )

        val UI = ToolSpec(
            "ui",
            "Shows or hides one part of the IDE's frame from View ▸ Appearance: toolbar, navigation_bar, tool_window_bars, " +
                "status_bar or main_menu. Without on it toggles; with on it sets. Returns the state after the call.",
            listOf(
                Param("part", "toolbar, navigation_bar, tool_window_bars, status_bar or main_menu"),
                Param("on", "true to show, false to hide (default: toggle)", type = "boolean", required = false),
            ),
            mutates = true,
        )
    }
}
