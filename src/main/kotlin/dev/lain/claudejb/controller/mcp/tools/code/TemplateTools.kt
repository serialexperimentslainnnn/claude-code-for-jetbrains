package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.TemplateImpl
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.codeInsight.template.impl.TemplateSettings
import com.intellij.ide.fileTemplates.FileTemplate
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.ide.fileTemplates.FileTemplateUtil
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.writeCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiManager
import dev.lain.claudejb.controller.mcp.FocusKeeper
import dev.lain.claudejb.controller.mcp.Reveal
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Properties

internal class TemplateTools(private val project: Project, private val targets: TargetContext, private val reveal: Reveal) {

    fun domain(): ToolDomain = ToolDomain(
        "templates",
        "The IDE's live templates and file templates: list them, expand a live template at a position, create a file " +
            "from a file template",
        listOf(
            Tool(TEMPLATES, ::templates),
            Tool(TEMPLATE_APPLY, ::apply),
            Tool(FILE_TEMPLATES, ::fileTemplates),
            Tool(FILE_FROM_TEMPLATE, ::fromTemplate),
        ),
    )

    private suspend fun templates(args: ToolArgs): ToolResult {
        val query = args.optionalString("query").orEmpty()
        val max = args.int("max", DEFAULT_MAX)
        val all = TemplateSettings.getInstance().templates
            .filter { !it.isDeactivated }
            .filter { query.isEmpty() || listOf(it.key, it.groupName, it.description.orEmpty()).any { s -> s.contains(query, true) } }
            .sortedWith(compareBy({ it.groupName }, { it.key }))
        return ToolResult.toon(
            buildJsonObject {
                put("query", query)
                put("count", all.size)
                put("truncated", all.size > max)
                put(
                    "templates",
                    buildJsonArray {
                        all.take(max).forEach { template ->
                            add(
                                buildJsonObject {
                                    put("key", template.key)
                                    put("group", template.groupName)
                                    put("description", template.description.orEmpty())
                                    put("text", template.string.take(TEXT_CHARS))
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    private suspend fun apply(args: ToolArgs): ToolResult {
        val key = args.string("key")
        val group = args.optionalString("group")
        val template = liveTemplate(key, group)
        args.string("path")
        val target = TargetContext.target(args, preview = false)
        val context = targets.of(target)
        withContext(Dispatchers.EDT) {
            val editor = applicableEditor(context, template, target)
            FocusKeeper.keeping(project) { TemplateManager.getInstance(project).startTemplate(editor, template) }
        }
        return ToolResult.toon(
            buildJsonObject {
                put("key", key)
                put("group", template.groupName)
                put("path", target.path)
                put("line", target.line)
                put("started", true)
            },
        )
    }

    private fun applicableEditor(context: DataContext, template: TemplateImpl, target: TargetContext.Target): Editor {
        val editor = CommonDataKeys.EDITOR.getData(context) ?: throw ToolException("no editor could be opened on ${target.path}")
        val psiFile = CommonDataKeys.PSI_FILE.getData(context)
        val applies = psiFile != null && TemplateManagerImpl.isApplicable(template, TemplateActionContext.expanding(psiFile, editor))
        if (!applies) {
            throw ToolException("the live template ${template.key} does not apply at ${target.path}:${target.line} (${template.groupName})")
        }
        return editor
    }

    private fun liveTemplate(key: String, group: String?): TemplateImpl =
        TemplateSettings.getInstance().templates.firstOrNull { it.key == key && (group == null || it.groupName == group) }
            ?: throw ToolException("no live template $key${group?.let { " in group $it" } ?: ""}; templates lists them")

    private suspend fun fileTemplates(args: ToolArgs): ToolResult {
        val query = args.optionalString("query").orEmpty()
        val max = args.int("max", DEFAULT_MAX)
        val manager = FileTemplateManager.getInstance(project)
        val all = (manager.allTemplates.toList() + manager.internalTemplates.toList())
            .filter { query.isEmpty() || it.name.contains(query, true) || it.extension.contains(query, true) }
            .sortedBy { it.name }
        return ToolResult.toon(
            buildJsonObject {
                put("query", query)
                put("count", all.size)
                put("truncated", all.size > max)
                put(
                    "templates",
                    buildJsonArray {
                        all.take(max).forEach { template ->
                            add(
                                buildJsonObject {
                                    put("name", template.name)
                                    put("extension", template.extension)
                                    put("text", template.text.take(TEXT_CHARS))
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    private suspend fun fromTemplate(args: ToolArgs): ToolResult {
        val name = args.string("template")
        val dir = args.string("dir")
        val fileName = args.string("name")
        val manager = FileTemplateManager.getInstance(project)
        val template = fileTemplate(manager, name)
        val properties = Properties(manager.defaultProperties).apply {
            (args.json["props"] as? JsonObject)?.forEach { (k, v) -> setProperty(k, (v as? JsonPrimitive)?.content ?: v.toString()) }
        }
        val directory = directory(dir)
        val created = writeCommandAction(project, "Claude: new $fileName from $name") {
            runCatching { FileTemplateUtil.createFromTemplate(template, fileName, properties, directory) }
                .getOrElse { throw ToolException("the template could not be applied: ${it.message}", it) }
        }
        val file = readAction { created.containingFile?.virtualFile } ?: throw ToolException("the template produced no file")
        reveal.file(file)
        return ToolResult.toon(
            buildJsonObject {
                put("template", name)
                put("path", Locations.relative(project, file))
                put("created", true)
            },
        )
    }

    private fun fileTemplate(manager: FileTemplateManager, name: String): FileTemplate =
        manager.getTemplate(name) ?: manager.internalTemplates.firstOrNull { it.name == name }
            ?: throw ToolException("no file template named $name; file_templates lists them")

    private suspend fun directory(dir: String): PsiDirectory = readAction {
        Locations.inside(project, dir)
        PsiManager.getInstance(project).findDirectory(ReadTools.resolveDirectory(project, dir))
            ?: throw ToolException("$dir is not a directory of this project")
    }

    companion object {

        private const val DEFAULT_MAX = 100
        private const val TEXT_CHARS = 200

        val TEMPLATES = ToolSpec(
            "templates",
            "The IDE's live templates (Settings ▸ Editor ▸ Live Templates): key, group, description and text, filtered " +
                "by a fragment; use it before template_apply.",
            listOf(
                Param("query", "Fragment of the key, group or description (default: all)", required = false),
                Param("max", "Maximum templates (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )

        val TEMPLATE_APPLY = ToolSpec(
            "template_apply",
            "Expands a live template at a position of a file, as typing its key and Tab would: the IDE's template editor " +
                "runs in the file's tab, without taking the focus, and its variables are the user's to fill or accept.",
            listOf(
                Param("key", "The template key, e.g. sout, fori, main"),
                Param("group", "The template group when the key is ambiguous", required = false),
                Param("path", "File path, absolute or relative to the project root"),
                Param("line", "1-based line for the caret", type = "integer"),
                Param("column", "1-based column for the caret (default 1)", type = "integer", required = false),
            ),
            mutates = true,
        )

        val FILE_TEMPLATES = ToolSpec(
            "file_templates",
            "The IDE's file templates (Settings ▸ Editor ▸ File and Code Templates): name, extension and text, filtered by " +
                "a fragment; use it before file_from_template.",
            listOf(
                Param("query", "Fragment of the name or extension (default: all)", required = false),
                Param("max", "Maximum templates (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )

        val FILE_FROM_TEMPLATE = ToolSpec(
            "file_from_template",
            "Creates a file from a file template in a directory, as New ▸ <template> does, with the IDE's default " +
                "properties plus props (NAME, PACKAGE_NAME…); the new file opens in the editor.",
            listOf(
                Param("template", "The file template name, as file_templates lists it"),
                Param("dir", "Directory, relative to the project root"),
                Param("name", "The new file's name without extension"),
                Param("props", "Template properties as an object of strings (default: the IDE's)", type = "object", required = false),
            ),
            mutates = true,
        )
    }
}
