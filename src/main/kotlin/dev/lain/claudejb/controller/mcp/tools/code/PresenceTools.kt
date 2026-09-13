package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.ide.scratch.ScratchRootType
import com.intellij.lang.Language
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import dev.lain.claudejb.controller.mcp.Reveal
import dev.lain.claudejb.model.mcp.Items
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class PresenceTools(private val project: Project, private val reveal: Reveal) {

    fun domain(): ToolDomain = ToolDomain(
        "presence",
        "Claude's presence in the IDE beyond the chat: a banner over a file's editor with choices, the status bar's " +
            "text, and scratch files",
        listOf(Tool(BANNER_SHOW, ::bannerShow), Tool(BANNER_CLEAR, ::bannerClear), Tool(STATUS, ::status), Tool(SCRATCH_CREATE, ::scratch)),
    )

    private suspend fun bannerShow(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val text = args.string("text")
        val actions = args.strings("actions")
        val file = readAction { Locations.file(project, path) }
        withContext(Dispatchers.EDT) { project.getService(BannerRegistry::class.java).show(file, text, actions) }
        reveal.file(file)
        return ToolResult.toon(
            buildJsonObject {
                put("path", path)
                put("text", text)
                put("actions", buildJsonArray { actions.forEach { add(JsonPrimitive(it)) } })
                put("shown", true)
            },
        )
    }

    private suspend fun bannerClear(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val file = readAction { Locations.file(project, path) }
        val banner = withContext(Dispatchers.EDT) { project.getService(BannerRegistry::class.java).clear(file) }
        return ToolResult.toon(
            buildJsonObject {
                put("path", path)
                put("cleared", banner != null)
                put("chosen", banner?.chosen ?: "")
            },
        )
    }

    private suspend fun status(args: ToolArgs): ToolResult {
        val text = args.string("text")
        val shown = withContext(Dispatchers.EDT) {
            val bar = WindowManager.getInstance().getStatusBar(project) ?: return@withContext false
            bar.info = text
            true
        }
        return ToolResult.toon(
            buildJsonObject {
                put("text", text)
                put("shown", shown)
            },
        )
    }

    private suspend fun scratch(args: ToolArgs): ToolResult {
        val name = args.string("name")
        val content = args.optionalString("content").orEmpty()
        val languageId = args.optionalString("language")
        val language = languageId?.let { Language.findLanguageByID(it) ?: throw ToolException("this IDE has no language with id $it") }
        val file = withContext(Dispatchers.EDT) { ScratchRootType.getInstance().createScratchFile(project, name, language, content) }
            ?: throw ToolException("the IDE did not create the scratch file $name")
        reveal.file(file)
        return ToolResult.toon(
            buildJsonObject {
                put("name", file.name)
                put("path", file.path)
                put("language", language?.id ?: "")
                put("created", true)
            },
        )
    }

    companion object {

        val BANNER_SHOW = ToolSpec(
            "banner_show",
            "Shows a notification banner over a file's editor, as the IDE does for its own notices, with a text and " +
                "optional action labels the user can click; the banner stays until banner_clear, a click on Dismiss, or a " +
                "click on an action, which banner_clear then reports as chosen. The file opens in a tab without focus.",
            listOf(
                Param("path", "File path, absolute or relative to the project root"),
                Param("text", "The banner text"),
                Param("actions", "Action labels to offer, in order", type = "array", required = false, items = Items("string")),
            ),
            mutates = true,
        )

        val BANNER_CLEAR = ToolSpec(
            "banner_clear",
            "Removes the banner from a file's editor and returns which action the user chose, if any.",
            listOf(Param("path", "File path, absolute or relative to the project root")),
            mutates = true,
        )

        val STATUS = ToolSpec(
            "status",
            "Puts a short text in the IDE's status bar, where the IDE reports its own progress; the next IDE message " +
                "replaces it.",
            listOf(Param("text", "The status text")),
            mutates = true,
        )

        val SCRATCH_CREATE = ToolSpec(
            "scratch_create",
            "Creates a scratch file (Scratches and Consoles) with a name, an optional language for highlighting and initial " +
                "content, and opens it in the editor without focus; it lives outside the project and is never committed.",
            listOf(
                Param("name", "File name with extension, e.g. notes.md, query.sql"),
                Param("language", "Language id for highlighting (default: from the extension)", required = false),
                Param("content", "Initial content (default empty)", required = false),
            ),
            mutates = true,
        )
    }
}
