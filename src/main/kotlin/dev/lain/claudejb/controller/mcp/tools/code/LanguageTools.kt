package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.lang.Language
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.command.writeCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import dev.lain.claudejb.controller.mcp.IdeActions
import dev.lain.claudejb.controller.mcp.TargetContext
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import dev.lain.claudejb.util.InstalledPlugins
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class LanguageTools(private val project: Project, private val actions: IdeActions) {

    fun domain(): ToolDomain = ToolDomain(
        "language",
        "Languages inside a file: the fragments the IDE injects (SQL in a string, regex, HTML), a temporary injection at " +
            "a position, and the quick documentation of a symbol",
        listOf(Tool(INJECTIONS, ::injections), Tool(INJECT_AT, ::injectAt), Tool(DOCS, ::docs)),
    )

    private suspend fun injections(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val max = args.int("max", DEFAULT_MAX)
        val rows = smartReadAction(project) {
            val psiFile = Locations.psiFile(project, path)
            val document = psiFile.viewProvider.document
            val manager = InjectedLanguageManager.getInstance(project)
            PsiTreeUtil.collectElementsOfType(psiFile, PsiLanguageInjectionHost::class.java).flatMap { host ->
                manager.getInjectedPsiFiles(host).orEmpty().map { pair ->
                    val injected = pair.first
                    val range = pair.second
                    buildJsonObject {
                        put("line", document?.getLineNumber(host.textOffset)?.plus(1) ?: 0)
                        put("host", host.text.take(TEXT_CHARS))
                        put("language", injected.language.id)
                        put("text", range.substring(host.text).take(TEXT_CHARS))
                    }
                }
            }
        }
        return ToolResult.toon(
            buildJsonObject {
                put("path", path)
                put("count", rows.size)
                put("truncated", rows.size > max)
                put("injections", buildJsonArray { rows.take(max).forEach { add(it) } })
            },
        )
    }

    private suspend fun injectAt(args: ToolArgs): ToolResult {
        val languageId = args.string("language")
        val language = Language.findLanguageByID(languageId) ?: throw ToolException("this IDE has no language with id $languageId")
        val host = smartReadAction(project) {
            val position = Locations.locate(project, args)
            PsiTreeUtil.getParentOfType(position.psiFile.findElementAt(position.offset), PsiLanguageInjectionHost::class.java, false)
                ?: throw ToolException("nothing at that position can host an injection (a string literal can)")
        }
        val injected = IntelliLangGateway(project).inject(host, language.id)
        return ToolResult.toon(
            buildJsonObject {
                put("language", language.id)
                put("path", args.string("path"))
                put("line", args.int("line", 1))
                put("injected", injected)
            },
        )
    }

    private suspend fun docs(args: ToolArgs): ToolResult {
        val target = TargetContext.target(args)
        if (target.path == null) throw ToolException("docs needs path, line and column")
        actions.dispatch(QUICK_DOC, target)
        return ToolResult.toon(
            buildJsonObject {
                put("path", target.path)
                put("line", target.line)
                put("column", target.column)
                put("dispatched", true)
            },
        )
    }

    companion object {

        private const val DEFAULT_MAX = 100
        private const val TEXT_CHARS = 120
        private const val QUICK_DOC = "QuickJavaDoc"

        val INJECTIONS = ToolSpec(
            "injections",
            "The language fragments the IDE injects into a file's literals (SQL, regex, JSON, HTML…): host line, host text, " +
                "injected language and fragment text.",
            listOf(
                Param("path", "File path, absolute or relative to the project root"),
                Param("max", "Maximum fragments (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )

        val INJECT_AT = ToolSpec(
            "inject_at",
            "Injects a language into the literal at a position, as the IDE's Inject Language intention does, through the " +
                "IntelliLang plugin's temporary injections; the editor highlights the fragment at once.",
            listOf(
                Param("path", "File path, absolute or relative to the project root"),
                Param("line", "1-based line of the literal", type = "integer"),
                Param("column", "1-based column inside the literal (default 1)", type = "integer", required = false),
                Param("language", "The language id, e.g. SQL, RegExp, JSON, HTML"),
            ),
            mutates = true,
        )

        val DOCS = ToolSpec(
            "docs",
            "Shows the IDE's quick documentation of the symbol at a position, in its popup, without taking the focus; " +
                "symbol_info gives the signature as data.",
            listOf(
                Param("path", "File path, absolute or relative to the project root"),
                Param("line", "1-based line of the symbol", type = "integer"),
                Param("column", "1-based column of the symbol (default 1)", type = "integer", required = false),
            ),
        )
    }
}

internal class IntelliLangGateway(private val project: Project) {

    suspend fun inject(host: PsiLanguageInjectionHost, languageId: String): Boolean {
        if (!InstalledPlugins.isEnabled(PLUGIN_ID)) {
            throw ToolException("the IntelliLang plugin ($PLUGIN_ID) is not installed or is disabled, so nothing injects languages")
        }
        val loader = javaClass.classLoader
        return writeCommandAction(project, "Claude: inject $languageId") {
            runCatching {
                val registryClass = Class.forName(REGISTRY, true, loader)
                val registry = registryClass.getMethod("getInstance", Project::class.java).invoke(null, project)
                val injectedClass = Class.forName(INJECTED_LANGUAGE, true, loader)
                val language = injectedClass.getMethod("create", String::class.java).invoke(null, languageId)
                val add = registryClass.getMethod("addHostWithUndo", PsiLanguageInjectionHost::class.java, injectedClass)
                add.invoke(registry, host, language)
                true
            }.getOrElse { throw ToolException("IntelliLang refused the injection: ${it.message}", it) }
        }
    }

    private companion object {
        const val PLUGIN_ID = "org.intellij.intelliLang"
        const val REGISTRY = "org.intellij.plugins.intelliLang.inject.TemporaryPlacesRegistry"
        const val INJECTED_LANGUAGE = "org.intellij.plugins.intelliLang.inject.InjectedLanguage"
    }
}
