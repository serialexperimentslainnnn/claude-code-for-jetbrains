package dev.lain.claudejb.controller.mcp.tools.ops

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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class ToolsMenuTools(private val project: Project, private val actions: IdeActions) {

    fun domain(): ToolDomain = ToolDomain(
        "tools_menu",
        "The Tools menu's generators and converters: Javadoc, the command-line launcher and desktop entry, XML " +
            "validation and schema generation, Markdown import and export",
        listOf(Tool(JAVADOC, ::javadoc), Tool(LAUNCHER, ::launcher), Tool(XML, ::xml), Tool(MARKDOWN, ::markdown)),
    )

    private suspend fun javadoc(args: ToolArgs): ToolResult = fire(GENERATE_JAVADOC, args, args.optionalString("path"))

    private suspend fun launcher(args: ToolArgs): ToolResult {
        val action = args.optionalString("action") ?: "script"
        val id = LAUNCHER_ACTIONS[action] ?: throw ToolException("action must be one of ${LAUNCHER_ACTIONS.keys.joinToString()}")
        return fire(id, args, null)
    }

    private suspend fun xml(args: ToolArgs): ToolResult {
        val action = args.string("action")
        val id = XML_ACTIONS[action] ?: throw ToolException("action must be one of ${XML_ACTIONS.keys.joinToString()}")
        return fire(id, args, args.string("path"))
    }

    private suspend fun markdown(args: ToolArgs): ToolResult {
        val action = args.string("action")
        val id = MARKDOWN_ACTIONS[action] ?: throw ToolException("action must be one of ${MARKDOWN_ACTIONS.keys.joinToString()}")
        return fire(id, args, args.optionalString("path"))
    }

    private suspend fun fire(id: String, args: ToolArgs, path: String?): ToolResult {
        val target = TargetContext.target(args)
        if (path != null) actions.dispatch(id, target) else actions.dispatch(id)
        return ToolResult.toon(
            buildJsonObject {
                put("id", id)
                put("path", path ?: "")
                put("project", project.name)
                put("dispatched", true)
            },
        )
    }

    companion object {

        private const val GENERATE_JAVADOC = "GenerateJavadoc"

        val LAUNCHER_ACTIONS: Map<String, String> = linkedMapOf(
            "script" to "CreateLauncherScript",
            "desktop_entry" to "CreateDesktopEntry",
        )

        val XML_ACTIONS: Map<String, String> = linkedMapOf(
            "validate" to "ValidateXml",
            "dtd" to "GenerateDTD",
            "schema" to "XSD2Document",
        )

        val MARKDOWN_ACTIONS: Map<String, String> = linkedMapOf(
            "import_docx" to "Markdown.ImportFromDocx",
            "export" to "Markdown.Export",
            "toc" to "Markdown.GenerateTableOfContents",
            "pandoc" to "Markdown.ConfigurePandoc",
        )

        val JAVADOC = ToolSpec(
            "javadoc",
            "Tools ▸ Generate JavaDoc: the IDE's dialog opens, scoped to path when given (a file or directory), for the " +
                "user to set the output and run; Java projects only.",
            listOf(Param("path", "File or directory to scope the dialog to (default: the project)", required = false)),
            mutates = true,
        )

        val LAUNCHER = ToolSpec(
            "launcher",
            "Tools ▸ Create Command-line Launcher (action=script, the default) or Create Desktop Entry (action=desktop_entry): " +
                "the IDE's dialog opens for the user.",
            listOf(Param("action", "script (default) or desktop_entry", required = false)),
            mutates = true,
        )

        val XML = ToolSpec(
            "xml",
            "Tools ▸ XML Actions on a file: validate (errors land in the Problems view), dtd (Generate DTD from XML) or " +
                "schema (Generate XML Schema from an XSD, XMLBeans).",
            listOf(
                Param("action", "validate, dtd or schema"),
                Param("path", "The XML or XSD file, relative to the project root"),
            ),
            mutates = true,
        )

        val MARKDOWN = ToolSpec(
            "markdown",
            "Tools ▸ Markdown: import_docx converts a .docx to Markdown, export converts a Markdown file to PDF, HTML or DOCX " +
                "through the IDE's dialog, toc generates a table of contents into the file, pandoc opens the converter's settings.",
            listOf(
                Param("action", "import_docx, export, toc or pandoc"),
                Param("path", "The Markdown file, relative to the project root (export, toc)", required = false),
            ),
            mutates = true,
        )
    }
}
