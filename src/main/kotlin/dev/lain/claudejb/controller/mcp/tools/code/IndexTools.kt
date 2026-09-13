package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.stubs.StubIndexExtension
import com.intellij.psi.stubs.StubIndexKey
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.ID
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class IndexTools(private val project: Project) {

    fun domain(): ToolDomain = ToolDomain(
        "index",
        "The IDE's file-based and stub indexes, by name: their keys, and the files or elements behind one key",
        listOf(Tool(INDEX_KEYS, ::keys), Tool(INDEX_QUERY, ::query), Tool(STUB_QUERY, ::stubQuery)),
    )

    private fun index(name: String): ID<Any, Any> {
        @Suppress("UNCHECKED_CAST")
        return ID.findByName<Any, Any>(name) as ID<Any, Any>? ?: throw ToolException("no file-based index named $name")
    }

    private suspend fun keys(args: ToolArgs): ToolResult {
        val name = args.string("index")
        val max = args.int("max", DEFAULT_MAX)
        val keys = smartReadAction(project) { FileBasedIndex.getInstance().getAllKeys(index(name), project).map { it.toString() }.sorted() }
        return ToolResult.toon(
            buildJsonObject {
                put("index", name)
                put("count", keys.size)
                put("truncated", keys.size > max)
                put("keys", buildJsonArray { keys.take(max).forEach { add(JsonPrimitive(it)) } })
            },
        )
    }

    private suspend fun query(args: ToolArgs): ToolResult {
        val name = args.string("index")
        val key = args.string("key")
        val max = args.int("max", DEFAULT_MAX)
        val files = smartReadAction(project) {
            val id = index(name)
            val scope = GlobalSearchScope.projectScope(project)
            val exact = FileBasedIndex.getInstance().getAllKeys(id, project).firstOrNull { it.toString() == key }
                ?: throw ToolException("no key $key in $name; index_keys lists them")
            FileBasedIndex.getInstance().getContainingFiles(id, exact, scope).map { Locations.relative(project, it) }.sorted()
        }
        return ToolResult.toon(
            buildJsonObject {
                put("index", name)
                put("key", key)
                put("count", files.size)
                put("truncated", files.size > max)
                put("files", buildJsonArray { files.take(max).forEach { add(JsonPrimitive(it)) } })
            },
        )
    }

    private suspend fun stubQuery(args: ToolArgs): ToolResult {
        val name = args.string("index")
        val key = args.optionalString("key")
        val max = args.int("max", DEFAULT_MAX)
        val indexKey = stubIndex(name)
        val rows = smartReadAction(project) {
            val stubs = StubIndex.getInstance()
            if (key == null) {
                stubs.getAllKeys(indexKey, project).map { it.toString() }.sorted().map { buildJsonObject { put("key", it) } }
            } else {
                val scope = GlobalSearchScope.projectScope(project)
                StubIndex.getElements(indexKey, key, project, scope, PsiElement::class.java).map { element ->
                    buildJsonObject {
                        put("key", key)
                        put("file", element.containingFile?.virtualFile?.let { Locations.relative(project, it) } ?: "")
                        put("line", element.containingFile?.viewProvider?.document?.getLineNumber(element.textOffset)?.plus(1) ?: 0)
                        put("text", element.text.lineSequence().firstOrNull()?.trim()?.take(TEXT_CHARS) ?: "")
                    }
                }
            }
        }
        return ToolResult.toon(
            buildJsonObject {
                put("index", name)
                put("key", key ?: "")
                put("count", rows.size)
                put("truncated", rows.size > max)
                put("rows", buildJsonArray { rows.take(max).forEach { add(it) } })
            },
        )
    }

    private fun stubIndex(name: String): StubIndexKey<String, PsiElement> {
        @Suppress("UNCHECKED_CAST")
        return StubIndexExtension.EP_NAME.extensionList.firstOrNull { it.key.name == name }?.key as StubIndexKey<String, PsiElement>?
            ?: throw ToolException("no stub index named $name; the registered ones are ${stubIndexNames()}")
    }

    private fun stubIndexNames(): String = StubIndexExtension.EP_NAME.extensionList.joinToString { it.key.name }

    companion object {

        private const val DEFAULT_MAX = 200
        private const val TEXT_CHARS = 120

        val INDEX_KEYS = ToolSpec(
            "index_keys",
            "The keys of a file-based index by its registered name (e.g. FilenameIndex, filetypes, TodoIndex, or a " +
                "plugin's), as the IDE holds them for this project.",
            listOf(
                Param("index", "The index id as registered by its ID.create name"),
                Param("max", "Maximum keys (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )

        val INDEX_QUERY = ToolSpec(
            "index_query",
            "The project files a file-based index holds under one key (the key as index_keys prints it).",
            listOf(
                Param("index", "The index id"),
                Param("key", "The key, as index_keys prints it"),
                Param("max", "Maximum files (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )

        val STUB_QUERY = ToolSpec(
            "stub_query",
            "A stub index by its name (e.g. java.class.shortname, org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex): " +
                "its keys without key, or the elements behind a key with file, line and text.",
            listOf(
                Param("index", "The stub index key name"),
                Param("key", "The key to look up (default: list the keys)", required = false),
                Param("max", "Maximum rows (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )
    }
}
