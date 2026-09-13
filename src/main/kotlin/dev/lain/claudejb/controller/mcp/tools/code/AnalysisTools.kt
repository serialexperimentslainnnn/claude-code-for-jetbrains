package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testIntegration.TestFinderHelper
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
import java.awt.datatransfer.StringSelection

internal class AnalysisTools(private val project: Project, private val actions: IdeActions) {

    fun domain(): ToolDomain = ToolDomain(
        "analysis",
        "The rest of Code ▸ Analyze and Navigate: a stack trace resolved to the project's files, duplicate code, " +
            "inferred nullity, and what is related to a symbol (its tests, its subject, its supers, its implementations)",
        listOf(
            Tool(STACK_TRACE, ::stackTrace),
            Tool(DUPLICATES, ::duplicates),
            Tool(INFER_NULLITY, ::inferNullity),
            Tool(RELATED, ::related),
        ),
    )

    private suspend fun stackTrace(args: ToolArgs): ToolResult {
        val text = args.string("text")
        val frames = smartReadAction(project) { frames(text) }
        withContext(Dispatchers.EDT) { CopyPasteManager.getInstance().setContents(StringSelection(text)) }
        actions.dispatch(UNSCRAMBLE)
        return ToolResult.toon(
            buildJsonObject {
                put("count", frames.size)
                put("frames", buildJsonArray { frames.forEach { add(it) } })
                put("dispatched", true)
            },
        )
    }

    private fun frames(text: String): List<JsonObject> = FRAME.findAll(text).map { match ->
        val (symbol, fileName, line) = match.destructured
        val found = FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project))
            .map { Locations.relative(project, it) }
            .sorted()
        buildJsonObject {
            put("symbol", symbol)
            put("file", found.firstOrNull() ?: fileName)
            put("line", line.toIntOrNull() ?: 0)
            put("resolved", found.isNotEmpty())
        }
    }.toList()

    private suspend fun duplicates(args: ToolArgs): ToolResult = fire(DUPLICATES_ACTION, args)

    private suspend fun inferNullity(args: ToolArgs): ToolResult = fire(INFER_NULLITY_ACTION, args)

    private suspend fun fire(id: String, args: ToolArgs): ToolResult {
        val target = TargetContext.target(args)
        if (target.named) actions.dispatch(id, target) else actions.dispatch(id)
        return ToolResult.toon(
            buildJsonObject {
                put("id", id)
                put("path", target.path ?: "")
                put("dispatched", true)
            },
        )
    }

    private suspend fun related(args: ToolArgs): ToolResult {
        val kind = args.string("kind")
        val id = RELATED_ACTIONS[kind] ?: throw ToolException("kind must be one of ${RELATED_ACTIONS.keys.joinToString()}")
        val target = TargetContext.target(args)
        if (target.path == null) throw ToolException("related needs path, line and column")
        val rows = if (kind == "test" || kind == "subject") smartReadAction(project) { tests(args, kind) } else emptyList()
        actions.dispatch(id, target)
        return ToolResult.toon(
            buildJsonObject {
                put("kind", kind)
                put("count", rows.size)
                put("related", buildJsonArray { rows.forEach { add(it) } })
                put("dispatched", true)
            },
        )
    }

    private fun tests(args: ToolArgs, kind: String): List<JsonObject> {
        val position = Locations.locate(project, args)
        val element = position.psiFile.findElementAt(position.offset) ?: position.psiFile
        val source = TestFinderHelper.findSourceElement(element) ?: element
        val found = if (kind == "test") TestFinderHelper.findTestsForClass(source) else TestFinderHelper.findClassesForTest(source)
        return found.map(::row)
    }

    private fun row(element: PsiElement): JsonObject = buildJsonObject {
        val file = element.containingFile
        val document = file?.viewProvider?.document
        put("file", file?.virtualFile?.let { Locations.relative(project, it) } ?: "")
        put("line", document?.getLineNumber(element.textOffset)?.plus(1) ?: 0)
        put("text", element.text.lineSequence().firstOrNull()?.trim()?.take(TEXT_CHARS) ?: "")
    }

    companion object {

        private const val TEXT_CHARS = 120
        private const val UNSCRAMBLE = "Unscramble"
        private const val DUPLICATES_ACTION = "MethodDuplicates"
        private const val INFER_NULLITY_ACTION = "InferNullity"

        private val FRAME = Regex("""at\s+([\w$.<>]+)\(([\w.$-]+\.\w+):(\d+)\)""")

        val RELATED_ACTIONS: Map<String, String> = linkedMapOf(
            "test" to "GotoTest",
            "subject" to "GotoTest",
            "super" to "GotoSuperMethod",
            "implementations" to "GotoImplementation",
        )

        val STACK_TRACE = ToolSpec(
            "stack_trace",
            "Code ▸ Analyze Stack Trace: the frames of a pasted trace resolved to the project's files and lines as data, and " +
                "the IDE's Analyze Stack Trace dialog opened with the text on the clipboard for the user to confirm into a " +
                "navigable console.",
            listOf(Param("text", "The stack trace text")),
        )

        val DUPLICATES = ToolSpec(
            "duplicates",
            "Code ▸ Analyze ▸ Locate Duplicates on a file or the project: the IDE's duplicate-code analysis opens in its " +
                "own window (Java and the languages that support it).",
            listOf(Param("path", "File to analyse, relative to the project root (default: the project)", required = false)),
        )

        val INFER_NULLITY = ToolSpec(
            "infer_nullity",
            "Code ▸ Analyze ▸ Infer Nullity on a file or the project: the IDE proposes @Nullable/@NotNull annotations in its " +
                "own dialog (Java).",
            listOf(Param("path", "File to analyse, relative to the project root (default: the project)", required = false)),
            mutates = true,
        )

        val RELATED = ToolSpec(
            "related",
            "What is related to the symbol at a position: kind=test the tests of the class, kind=subject the class a test " +
                "covers (both as data through the IDE's test finder), kind=super its super method, kind=implementations " +
                "what implements it; the IDE's Navigate action opens the target or its chooser.",
            listOf(
                Param("kind", "test, subject, super or implementations"),
                Param("path", "File path, absolute or relative to the project root"),
                Param("line", "1-based line of the symbol", type = "integer"),
                Param("column", "1-based column of the symbol (default 1)", type = "integer", required = false),
            ),
        )
    }
}
