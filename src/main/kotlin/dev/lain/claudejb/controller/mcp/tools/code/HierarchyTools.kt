package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import dev.lain.claudejb.controller.mcp.tools.code.Locations.place
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

internal class HierarchyTools(private val project: Project) {

    fun domain(): ToolDomain = ToolDomain(
        "hierarchy",
        "Who calls a symbol and what it calls, resolved by the index; language-agnostic",
        listOf(Tool(HIERARCHY, ::hierarchy)),
    )

    private suspend fun hierarchy(args: ToolArgs): ToolResult {
        val kind = args.string("kind")
        if (kind != CALLERS && kind != CALLEES) throw ToolException("kind must be $CALLERS or $CALLEES")
        val depth = args.int("depth", 1).coerceIn(1, MAX_DEPTH)
        val max = args.int("max", DEFAULT_MAX)
        val (root, rows) = try {
            readAction {
                val target = Locations.declarationAt(project, args)
                val walk = Walk(kind == CALLERS, depth, max)
                walk.from(target, 1)
                name(target) to walk.rows
            }
        } catch (e: IndexNotReadyException) {
            throw ToolException("the IDE is still indexing; retry in a moment", e)
        }
        return ToolResult.toon(
            buildJsonObject {
                put("kind", kind)
                put("root", root)
                put("count", rows.size)
                put("truncated", rows.size >= max)
                put("calls", buildJsonArray { rows.forEach { add(it) } })
            },
        )
    }

    private inner class Walk(private val callers: Boolean, private val depth: Int, private val max: Int) {

        val rows = ArrayList<JsonObject>()
        private val visited = HashSet<PsiElement>()

        fun from(element: PsiElement, level: Int) {
            visited += element
            if (level > depth) return
            for (related in if (callers) callersOf(element) else calleesOf(element)) {
                if (rows.size >= max) return
                if (!visited.add(related)) continue
                rows += row(level, related)
                from(related, level + 1)
            }
        }

        private fun callersOf(element: PsiElement): List<PsiElement> {
            val out = LinkedHashSet<PsiElement>()
            ReferencesSearch.search(element, GlobalSearchScope.projectScope(project)).forEach { reference ->
                PsiTreeUtil.getParentOfType(reference.element, PsiNameIdentifierOwner::class.java, true)?.let { out += it }
                out.size < max
            }
            return out.toList()
        }

        private fun calleesOf(element: PsiElement): List<PsiElement> {
            val out = LinkedHashSet<PsiElement>()
            val index = ProjectFileIndex.getInstance(project)
            element.navigationElement.accept(
                object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(node: PsiElement) {
                        super.visitElement(node)
                        for (reference in node.references) {
                            val resolved = reference.resolve() ?: continue
                            if (resolved is PsiNameIdentifierOwner && !resolved.isEquivalentTo(element) && inProject(index, resolved)) {
                                out += resolved
                            }
                        }
                        if (out.size >= max) stopWalking()
                    }
                },
            )
            return out.toList()
        }
    }

    private fun inProject(index: ProjectFileIndex, element: PsiElement): Boolean {
        val file = element.navigationElement.containingFile?.virtualFile ?: return false
        return index.isInContent(file)
    }

    private fun row(level: Int, element: PsiElement): JsonObject = buildJsonObject {
        put("depth", level)
        put("name", name(element))
        put("kind", Locations.kind(element))
        place(project, element)
    }

    private fun name(element: PsiElement): String = (element as? PsiNamedElement)?.name ?: ""

    companion object {

        private const val CALLERS = "callers"
        private const val CALLEES = "callees"
        private const val MAX_DEPTH = 3
        private const val DEFAULT_MAX = 50

        val HIERARCHY = ToolSpec(
            "hierarchy",
            "The call hierarchy of the symbol at a position: callers (who references it, by enclosing declaration) or " +
                "callees (the declarations it references), each level nested up to depth.",
            Locations.POSITION + listOf(
                Param("kind", "$CALLERS or $CALLEES"),
                Param("depth", "Levels to follow, 1 to $MAX_DEPTH (default 1)", type = "integer", required = false),
                Param("max", "Maximum rows to return (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )
    }
}
