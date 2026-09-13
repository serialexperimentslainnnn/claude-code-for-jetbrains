package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UFile
import org.jetbrains.uast.UastFacade
import org.jetbrains.uast.visitor.UastVisitor

internal class UastTools(private val project: Project) {

    fun domain(): ToolDomain = ToolDomain(
        "uast",
        "The unified AST (UAST) the IDE shares across Java, Kotlin, Scala and Groovy: the tree of a file to a depth, and " +
            "the element at a position with its parents",
        listOf(Tool(UAST_TREE, ::tree), Tool(UAST_AT, ::at)),
    )

    private suspend fun tree(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val depth = args.int("depth", DEFAULT_DEPTH)
        val max = args.int("max", DEFAULT_MAX)
        val rows = smartReadAction(project) {
            val psiFile = Locations.psiFile(project, path)
            val file = UastFacade.convertElementWithParent(psiFile, UFile::class.java) as? UFile
                ?: throw ToolException("UAST does not support ${psiFile.language.displayName}; psi_tree does")
            val out = ArrayList<JsonObject>()
            file.accept(
                object : UastVisitor {
                    private var level = 0

                    override fun visitElement(node: UElement): Boolean {
                        if (out.size >= max || level > depth) return true
                        out += row(node, level, psiFile)
                        level++
                        return false
                    }

                    override fun afterVisitElement(node: UElement) {
                        if (level > 0) level--
                    }
                },
            )
            out
        }
        return ToolResult.toon(
            buildJsonObject {
                put("path", path)
                put("count", rows.size)
                put("truncated", rows.size >= max)
                put("elements", buildJsonArray { rows.forEach { add(it) } })
            },
        )
    }

    private suspend fun at(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val rows = smartReadAction(project) {
            val position = Locations.locate(project, args)
            val leaf = position.psiFile.findElementAt(position.offset) ?: throw ToolException("nothing at that position")
            val element = generateSequence(leaf) { it.parent }
                .mapNotNull { UastFacade.convertElementWithParent(it, null) }
                .firstOrNull() ?: throw ToolException("UAST does not support ${position.psiFile.language.displayName}; psi_at does")
            generateSequence(element) { it.uastParent }.mapIndexed { index, node -> row(node, index, position.psiFile) }.toList()
        }
        return ToolResult.toon(
            buildJsonObject {
                put("path", path)
                put("count", rows.size)
                put("parents", buildJsonArray { rows.forEach { add(it) } })
            },
        )
    }

    private fun row(node: UElement, level: Int, file: PsiFile): JsonObject = buildJsonObject {
        val source = node.sourcePsi
        val document = file.viewProvider.document
        put("level", level)
        put("kind", node.javaClass.interfaces.firstOrNull { it.simpleName.startsWith("U") }?.simpleName ?: node.javaClass.simpleName)
        put("line", source?.textRange?.let { document?.getLineNumber(it.startOffset)?.plus(1) } ?: 0)
        put("text", (source?.text ?: node.asRenderString()).lineSequence().firstOrNull()?.trim()?.take(TEXT_CHARS) ?: "")
    }

    companion object {

        private const val DEFAULT_DEPTH = 3
        private const val DEFAULT_MAX = 300
        private const val TEXT_CHARS = 80

        val UAST_TREE = ToolSpec(
            "uast_tree",
            "The UAST of a file to a depth: level, node kind (UClass, UMethod, UCallExpression…), line and the first line of " +
                "source text, the same shape for every JVM language the IDE unifies.",
            listOf(
                Param("path", "File path, absolute or relative to the project root"),
                Param("depth", "Levels below the file (default $DEFAULT_DEPTH)", type = "integer", required = false),
                Param("max", "Maximum nodes (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )

        val UAST_AT = ToolSpec(
            "uast_at",
            "The UAST node at a position and its parents up to the file, innermost first.",
            listOf(
                Param("path", "File path, absolute or relative to the project root"),
                Param("line", "1-based line", type = "integer"),
                Param("column", "1-based column (default 1)", type = "integer", required = false),
            ),
        )
    }
}
