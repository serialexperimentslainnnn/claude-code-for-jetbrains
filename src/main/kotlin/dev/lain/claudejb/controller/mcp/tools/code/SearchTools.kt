package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.find.FindModel
import com.intellij.find.impl.FindInProjectUtil
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.usageView.UsageInfo
import com.intellij.usages.FindUsagesProcessPresentation
import com.intellij.usages.UsageViewPresentation
import dev.lain.claudejb.model.mcp.Batch
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.Collections

internal class SearchTools(private val project: Project, private val io: CoroutineDispatcher = Dispatchers.IO) {

    fun domain(): ToolDomain = ToolDomain(
        "search",
        "Text and file search over the project's content roots",
        listOf(
            Tool(SEARCH_TEXT) { ToolResult.toon(Batch.run(it, Batch.QUERIES, ::searchOne)) },
            Tool(FIND_FILES) { ToolResult.toon(Batch.run(it, Batch.NAMES, ::findOne)) },
            Tool(LIST_DIRECTORY, ::listDirectory),
        ),
    )

    private suspend fun searchOne(args: ToolArgs): JsonObject {
        val query = args.string("query")
        val max = args.int("max", DEFAULT_MAX)
        val model = FindModel().apply {
            stringToFind = query
            isRegularExpressions = args.boolean("regex", false)
            isCaseSensitive = args.boolean("case_sensitive", false)
            isProjectScope = true
            isWithSubdirectories = true
            args.optionalString("path")?.let {
                directoryName = ReadTools.resolveDirectory(project, it).path
                isProjectScope = false
            }
        }
        val hits = Collections.synchronizedList(ArrayList<UsageInfo>())
        withContext(io) {
            try {
                coroutineToIndicator { indicator ->
                    FindInProjectUtil.findUsages(model, project, indicator, PRESENTATION, emptySet()) { info ->
                        hits += info
                        hits.size < max
                    }
                }
            } catch (e: IndexNotReadyException) {
                throw ToolException("the IDE is still indexing; retry in a moment", e)
            }
        }
        val found = synchronized(hits) { hits.toList() }
        val rows = readAction { found.map { describe(it) }.distinct() }
        return buildJsonObject {
            put("query", query)
            put("count", rows.size)
            put("truncated", found.size >= max)
            put("matches", buildJsonArray { rows.forEach { add(it) } })
        }
    }

    private fun describe(info: UsageInfo): JsonObject = buildJsonObject {
        val file = info.virtualFile
        val document = file?.let { FileDocumentManager.getInstance().getDocument(it) }
        put("file", file?.let(::relative) ?: "")
        if (document != null) put("line", document.getLineNumber(info.navigationOffset) + 1)
    }

    private suspend fun findOne(args: ToolArgs): JsonObject {
        val name = args.string("name")
        val max = args.int("max", DEFAULT_MAX)
        val found = readAction {
            try {
                if ('*' in name || '?' in name) glob(name, max) else byName(name, max)
            } catch (e: IndexNotReadyException) {
                throw ToolException("the IDE is still indexing; retry in a moment", e)
            }
        }
        return buildJsonObject {
            put("name", name)
            put("count", found.size)
            put("truncated", found.size >= max)
            put("files", buildJsonArray { found.forEach { add(buildJsonObject { put("file", it) }) } })
        }
    }

    private fun byName(name: String, max: Int): List<String> =
        FilenameIndex.getVirtualFilesByName(name, false, GlobalSearchScope.projectScope(project)).map(::relative).sorted().take(max)

    private fun glob(pattern: String, max: Int): List<String> {
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
        val out = ArrayList<String>()
        ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (!file.isDirectory && matcher.matches(Path.of(file.name))) out += relative(file)
            out.size < max
        }
        return out.sorted()
    }

    private suspend fun listDirectory(args: ToolArgs): ToolResult {
        val path = args.optionalString("path") ?: "."
        val depth = args.int("depth", 1)
        val max = args.int("max", DEFAULT_MAX)
        if (depth < 1 || max < 1) throw ToolException("depth and max start at 1")
        val rows = readAction {
            val root = ReadTools.resolveDirectory(project, path)
            ArrayList<JsonObject>().also { walk(root, depth, ProjectFileIndex.getInstance(project), it, max) }
        }
        return ToolResult.toon(
            buildJsonObject {
                put("path", path)
                put("count", rows.size)
                put("truncated", rows.size >= max)
                put("entries", buildJsonArray { rows.forEach { add(it) } })
            },
        )
    }

    private fun walk(dir: VirtualFile, depth: Int, index: ProjectFileIndex, out: MutableList<JsonObject>, max: Int) {
        val children = dir.children.filterNot(index::isExcluded).sortedWith(compareBy({ !it.isDirectory }, { it.name }))
        for (child in children) {
            if (out.size >= max) return
            out += buildJsonObject {
                put("path", relative(child))
                put("kind", if (child.isDirectory) "dir" else "file")
                put("size", if (child.isDirectory) 0L else child.length)
            }
            if (child.isDirectory && depth > 1) walk(child, depth - 1, index, out, max)
        }
    }

    private fun relative(file: VirtualFile): String = Locations.relative(project, file)

    companion object {

        private const val DEFAULT_MAX = 50
        private val PRESENTATION = FindUsagesProcessPresentation(UsageViewPresentation())

        val SEARCH_TEXT = ToolSpec(
            "search_text",
            "Finds text or a regular expression across the project: one row per matching line with file and line, no text; " +
                "read_file the lines you need.",
            listOf(
                Param("query", "Text or regular expression to find", required = false),
                Batch.param(Batch.QUERIES, "Several searches at once, one result per query; the other arguments apply to each"),
                Param("regex", "true to treat query as a regular expression (default false)", type = "boolean", required = false),
                Param("case_sensitive", "true to match case (default false)", type = "boolean", required = false),
                Param("path", "Directory to search under, relative to the project root (default: whole project)", required = false),
                Param("max", "Maximum matches to return (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )

        val FIND_FILES = ToolSpec(
            "find_files",
            "Finds files by exact name or by glob (for example *.kt or Test?.java) across the project's content roots.",
            listOf(
                Param("name", "Exact file name, or a glob on the file name", required = false),
                Batch.param(Batch.NAMES, "Several names or globs at once, one result per name"),
                Param("max", "Maximum files to return (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )

        val LIST_DIRECTORY = ToolSpec(
            "list_directory",
            "Lists a directory as the project tree shows it, excluded and ignored entries left out: one row per entry " +
                "with path, kind (dir or file) and size, directories first. Use it to see the shape of a directory; " +
                "to find a file by name use find_files.",
            listOf(
                Param("path", "Directory, absolute or relative to the project root (default: the project root)", required = false),
                Param("depth", "How many levels to descend (default 1)", type = "integer", required = false),
                Param("max", "Maximum entries to return (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )
    }
}
