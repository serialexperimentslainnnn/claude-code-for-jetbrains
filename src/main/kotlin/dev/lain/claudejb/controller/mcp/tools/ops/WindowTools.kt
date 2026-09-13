package dev.lain.claudejb.controller.mcp.tools.ops

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.project.Project
import dev.lain.claudejb.controller.mcp.IdeActions
import dev.lain.claudejb.controller.mcp.TargetContext
import dev.lain.claudejb.controller.mcp.tools.code.Locations
import dev.lain.claudejb.controller.mcp.tools.code.ReadTools
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

internal class WindowTools(private val project: Project, private val actions: IdeActions) {

    fun domain(): ToolDomain = ToolDomain(
        "window",
        "The Window menu and the editor's tabs: list, close, pin and split tabs, the tool window layout, zoom, and the " +
            "editor's display settings",
        listOf(Tool(TABS, ::tabs), Tool(LAYOUT, ::layout), Tool(ZOOM, ::zoom), Tool(EDITOR_SETTINGS, ::editorSettings)),
    )

    private suspend fun tabs(args: ToolArgs): ToolResult {
        val action = args.optionalString("action") ?: "list"
        if (action != "list") act(action, args)
        val groups = withContext(Dispatchers.EDT) { FileEditorManagerEx.getInstanceEx(project).windows.mapIndexed(::group) }
        return ToolResult.toon(
            buildJsonObject {
                put("action", action)
                put("groups", buildJsonArray { groups.forEach { add(it) } })
            },
        )
    }

    private suspend fun act(action: String, args: ToolArgs) {
        val id = TAB_ACTIONS[action]
        if (action != "close" && id == null) throw ToolException("action must be list, close or one of ${TAB_ACTIONS.keys.joinToString()}")
        val target = TargetContext.target(args, preview = false)
        val path = target.path ?: throw ToolException("action=$action needs path")
        if (id != null) {
            actions.dispatch(id, target)
            return
        }
        val file = readAction { ReadTools.resolveFile(project, path) }
        withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).closeFile(file) }
    }

    private fun group(index: Int, window: EditorWindow): JsonObject = buildJsonObject {
        put("group", index + 1)
        put("selected", window.selectedFile?.let { Locations.relative(project, it) } ?: "")
        put(
            "tabs",
            buildJsonArray {
                window.fileList.forEach { file ->
                    add(
                        buildJsonObject {
                            put("path", Locations.relative(project, file))
                            put("pinned", window.isFilePinned(file))
                        },
                    )
                }
            },
        )
    }

    private suspend fun layout(args: ToolArgs): ToolResult {
        val action = args.string("action")
        val id = LAYOUT_ACTIONS[action] ?: throw ToolException("action must be one of ${LAYOUT_ACTIONS.keys.joinToString()}")
        actions.dispatch(id)
        return ToolResult.toon(
            buildJsonObject {
                put("action", action)
                put("dispatched", true)
            },
        )
    }

    private suspend fun zoom(args: ToolArgs): ToolResult {
        val action = args.string("action")
        val scope = args.optionalString("scope") ?: "editor"
        val id = zoomAction(scope, action)
        if (scope == "editor") actions.dispatch(id, selectedEditorContext()) else actions.dispatch(id)
        return ToolResult.toon(
            buildJsonObject {
                put("action", action)
                put("scope", scope)
                put("dispatched", true)
            },
        )
    }

    private fun zoomAction(scope: String, action: String): String {
        val table = when (scope) {
            "editor" -> EDITOR_ZOOM
            "ide" -> IDE_ZOOM
            else -> throw ToolException("scope must be editor or ide")
        }
        return table[action] ?: throw ToolException("action must be in, out or reset")
    }

    private suspend fun selectedEditorContext(): DataContext = withContext(Dispatchers.EDT) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: throw ToolException("no text editor is selected")
        DataManager.getInstance().getDataContext(editor.contentComponent)
    }

    private suspend fun editorSettings(args: ToolArgs): ToolResult {
        val setting = args.string("setting")
        if (setting !in EDITOR_SETTINGS_KEYS) throw ToolException("setting must be one of ${EDITOR_SETTINGS_KEYS.joinToString()}")
        val on = args.optionalBoolean("on")
        val now = withContext(Dispatchers.EDT) {
            val settings = EditorSettingsExternalizable.getInstance()
            val current = read(settings, setting)
            val wanted = on ?: !current
            if (wanted != current) {
                write(settings, setting, wanted)
                EditorFactory.getInstance().refreshAllEditors()
            }
            wanted
        }
        return ToolResult.toon(
            buildJsonObject {
                put("setting", setting)
                put("on", now)
            },
        )
    }

    private fun read(settings: EditorSettingsExternalizable, setting: String): Boolean = when (setting) {
        "line_numbers" -> settings.isLineNumbersShown
        "whitespace" -> settings.isWhitespacesShown
        "soft_wraps" -> settings.isUseSoftWraps
        else -> settings.areGutterIconsShown()
    }

    private fun write(settings: EditorSettingsExternalizable, setting: String, on: Boolean) = when (setting) {
        "line_numbers" -> settings.isLineNumbersShown = on
        "whitespace" -> settings.isWhitespacesShown = on
        "soft_wraps" -> settings.isUseSoftWraps = on
        else -> settings.setGutterIconsShown(on)
    }

    companion object {

        val TAB_ACTIONS: Map<String, String> = linkedMapOf(
            "close_others" to "CloseAllEditorsButActive",
            "close_all" to "CloseAllEditors",
            "pin" to "PinActiveTabToggle",
            "split_right" to "SplitVertically",
            "split_down" to "SplitHorizontally",
            "unsplit" to "Unsplit",
            "move_to_opposite" to "MoveEditorToOppositeTabGroup",
        )

        val LAYOUT_ACTIONS: Map<String, String> = linkedMapOf(
            "save_default" to "StoreDefaultLayout",
            "restore_default" to "RestoreDefaultLayout",
            "hide_all" to "HideAllWindows",
        )

        val EDITOR_ZOOM: Map<String, String> = linkedMapOf(
            "in" to "EditorIncreaseFontSize",
            "out" to "EditorDecreaseFontSize",
            "reset" to "EditorResetFontSize",
        )

        val IDE_ZOOM: Map<String, String> = linkedMapOf(
            "in" to "ZoomInIdeAction",
            "out" to "ZoomOutIdeAction",
            "reset" to "ResetIdeScaleAction",
        )

        val EDITOR_SETTINGS_KEYS: List<String> = listOf("line_numbers", "whitespace", "soft_wraps", "gutter_icons")

        val TABS = ToolSpec(
            "tabs",
            "The editor's tab groups with their tabs, the selected one and pins (action=list, the default); or acts on a " +
                "tab as the Window ▸ Editor Tabs menu would: close, close_others, close_all, pin (toggle), split_right, " +
                "split_down, unsplit, move_to_opposite. Returns the groups after the action.",
            listOf(
                Param(
                    "action",
                    "list (default), close, close_others, close_all, pin, split_right, split_down, unsplit or move_to_opposite",
                    required = false,
                ),
                Param("path", "The tab's file, for every action but list", required = false),
            ),
            mutates = true,
        )

        val LAYOUT = ToolSpec(
            "layout",
            "The tool window layout, as the Window menu does: save_default stores the current layout as the default, " +
                "restore_default brings it back, hide_all hides every tool window (and shows them again on a second call).",
            listOf(Param("action", "save_default, restore_default or hide_all")),
            mutates = true,
        )

        val ZOOM = ToolSpec(
            "zoom",
            "Zooms in, out or back to normal: the selected editor's font (scope=editor, the default) or the whole IDE " +
                "(scope=ide), as View ▸ Appearance ▸ Zoom does.",
            listOf(Param("action", "in, out or reset"), Param("scope", "editor (default) or ide", required = false)),
            mutates = true,
        )

        val EDITOR_SETTINGS = ToolSpec(
            "editor_settings",
            "Shows or hides one of the editor's display settings for every editor: line_numbers, whitespace, soft_wraps " +
                "or gutter_icons. Without on it toggles; with on it sets. Returns the state after the call.",
            listOf(
                Param("setting", "line_numbers, whitespace, soft_wraps or gutter_icons"),
                Param("on", "true to show, false to hide (default: toggle)", type = "boolean", required = false),
            ),
            mutates = true,
        )
    }
}
