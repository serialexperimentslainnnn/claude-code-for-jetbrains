package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.indexing.FindSymbolParameters
import dev.lain.claudejb.controller.mcp.tools.code.Locations.place
import dev.lain.claudejb.model.mcp.Batch
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

internal class NavigateTools(private val project: Project) {

    fun domain(): ToolDomain = ToolDomain(
        "navigate",
        "Symbols as the IDE resolves them: find by name, go to definition, references, implementations",
        listOf(
            Tool(FIND_SYMBOLS) { ToolResult.toon(Batch.run(it, Batch.QUERIES, ::findOne)) },
            Tool(DEFINITION) { ToolResult.toon(Batch.run(it, Batch.POSITIONS, ::definitionOne)) },
            Tool(REFERENCES) { ToolResult.toon(Batch.run(it, Batch.POSITIONS, ::referencesOne)) },
            Tool(IMPLEMENTATIONS) { ToolResult.toon(Batch.run(it, Batch.POSITIONS, ::implementationsOne)) },
        ),
    )

    private suspend fun findOne(args: ToolArgs): JsonObject {
        val query = args.string("query")
        val max = args.int("max", DEFAULT_MAX)
        val libraries = args.boolean("libraries", false)
        val rows = indexed {
            val scope = if (libraries) GlobalSearchScope.allScope(project) else GlobalSearchScope.projectScope(project)
            val parameters = FindSymbolParameters.simple(project, libraries)
            val items = LinkedHashSet<NavigationItem>()
            for (contributor in contributors()) {
                val names = LinkedHashSet<String>()
                contributor.processNames(
                    { name ->
                        if (name.contains(query, ignoreCase = true)) names += name
                        names.size < max
                    },
                    scope,
                    null,
                )
                for (name in names) {
                    if (items.size >= max) break
                    contributor.processElementsWithName(
                        name,
                        { item ->
                            items += item
                            items.size < max
                        },
                        parameters,
                    )
                }
                if (items.size >= max) break
            }
            items.filter { it is PsiElement && Locations.located(it) }.map(::symbolRow)
        }
        return buildJsonObject {
            put("query", query)
            table("symbols", rows, rows.size >= max)
        }
    }

    private suspend fun definitionOne(args: ToolArgs): JsonObject = indexed {
        val target = resolved(args)
        buildJsonObject {
            put("kind", Locations.kind(target))
            place(project, target)
        }
    }

    private suspend fun referencesOne(args: ToolArgs): JsonObject {
        val max = args.int("max", DEFAULT_MAX)
        val rows = indexed {
            val rows = ArrayList<JsonObject>()
            ReferencesSearch.search(resolved(args)).forEach { reference ->
                rows += Locations.describe(project, reference.element)
                rows.size < max
            }
            rows
        }
        return buildJsonObject { table("references", rows, rows.size >= max) }
    }

    private suspend fun implementationsOne(args: ToolArgs): JsonObject {
        val max = args.int("max", DEFAULT_MAX)
        val rows = indexed {
            val rows = ArrayList<JsonObject>()
            DefinitionsScopedSearch.search(resolved(args)).forEach { element ->
                rows += buildJsonObject {
                    put("kind", Locations.kind(element))
                    place(project, element)
                }
                rows.size < max
            }
            rows
        }
        return buildJsonObject { table("implementations", rows, rows.size >= max) }
    }

    private fun resolved(args: ToolArgs): PsiElement = Locations.declarationAt(project, args)

    private fun symbolRow(item: NavigationItem): JsonObject = buildJsonObject {
        put("name", item.name ?: "")
        put("kind", Locations.kind(item))
        place(project, item as PsiElement, withText = false)
        put("in", item.presentation?.locationString ?: "")
    }

    private fun contributors(): List<ChooseByNameContributorEx> =
        (ChooseByNameContributor.CLASS_EP_NAME.extensionList + ChooseByNameContributor.SYMBOL_EP_NAME.extensionList)
            .filterIsInstance<ChooseByNameContributorEx>()
            .distinct()

    private suspend fun <T> indexed(body: () -> T): T = try {
        readAction(body)
    } catch (e: IndexNotReadyException) {
        throw ToolException("the IDE is still indexing; retry in a moment", e)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.table(key: String, rows: List<JsonObject>, truncated: Boolean) {
        put("count", rows.size)
        put("truncated", truncated)
        put(key, buildJsonArray { rows.forEach { add(it) } })
    }

    companion object {

        private const val DEFAULT_MAX = 50

        val FIND_SYMBOLS = ToolSpec(
            "find_symbols",
            "Finds classes, functions and other named symbols whose name contains the query, as the IDE's Go to Symbol does; " +
                "several queries at once with queries.",
            listOf(
                Param("query", "Part of the symbol name, case-insensitive", required = false),
                Batch.param(Batch.QUERIES, "Several queries at once, one result per query; the other arguments apply to each"),
                Param("libraries", "true to include library symbols (default false)", type = "boolean", required = false),
                Param("max", "Maximum symbols to return (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )

        val DEFINITION = ToolSpec(
            "definition",
            "Resolves the reference at a position to its declaration and returns where it is; several positions at once " +
                "with positions.",
            Locations.OPTIONAL_POSITION + Batch.positions("the declaration each resolves to"),
        )

        val REFERENCES = ToolSpec(
            "references",
            "Lists the places that reference the symbol at a position; several positions at once with positions.",
            Locations.OPTIONAL_POSITION + Batch.positions("the references of each") +
                Param("max", "Maximum references to return (default $DEFAULT_MAX)", type = "integer", required = false),
        )

        val IMPLEMENTATIONS = ToolSpec(
            "implementations",
            "Lists the implementations or overrides of the symbol at a position; several positions at once with positions.",
            Locations.OPTIONAL_POSITION + Batch.positions("the implementations of each") +
                Param("max", "Maximum results to return (default $DEFAULT_MAX)", type = "integer", required = false),
        )
    }
}
