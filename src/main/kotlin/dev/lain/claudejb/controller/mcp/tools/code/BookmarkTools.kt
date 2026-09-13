package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.ide.bookmark.Bookmark
import com.intellij.ide.bookmark.BookmarkType
import com.intellij.ide.bookmark.BookmarksManager
import com.intellij.ide.bookmark.FileBookmark
import com.intellij.ide.bookmark.LineBookmark
import com.intellij.ide.bookmark.providers.LineBookmarkProvider
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.lain.claudejb.controller.mcp.FocusKeeper
import dev.lain.claudejb.controller.mcp.Reveal
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

internal class BookmarkTools(private val project: Project, private val reveal: Reveal) {

    fun domain(): ToolDomain = ToolDomain(
        "bookmarks",
        "The IDE's bookmarks and the project view: list, add and remove bookmarks on files and lines, and select a file in " +
            "the project view's pane",
        listOf(
            Tool(BOOKMARKS, ::bookmarks),
            Tool(BOOKMARK_ADD, ::add),
            Tool(BOOKMARK_REMOVE, ::remove),
            Tool(PROJECT_VIEW, ::projectView),
        ),
    )

    private fun manager(): BookmarksManager =
        BookmarksManager.getInstance(project) ?: throw ToolException("this IDE has no bookmarks manager")

    private suspend fun bookmarks(ignored: ToolArgs): ToolResult {
        val manager = manager()
        val rows = withContext(Dispatchers.EDT) {
            manager.groups.flatMap { group -> group.getBookmarks().map { row(manager, group.name, it, group.getDescription(it)) } }
        }
        return ToolResult.toon(
            buildJsonObject {
                put("count", rows.size)
                put("bookmarks", buildJsonArray { rows.forEach { add(it) } })
            },
        )
    }

    private fun row(manager: BookmarksManager, group: String, bookmark: Bookmark, description: String?): JsonObject = buildJsonObject {
        put("group", group)
        put("file", (bookmark as? FileBookmark)?.file?.let { Locations.relative(project, it) } ?: "")
        put("line", (bookmark as? LineBookmark)?.line?.plus(1) ?: 0)
        put("type", manager.getType(bookmark)?.mnemonic?.takeIf { it != BookmarkType.DEFAULT.mnemonic }?.toString() ?: "")
        put("description", description.orEmpty())
    }

    private suspend fun add(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val line = args.int("line", 0)
        val groupName = args.optionalString("group")
        val description = args.optionalString("description")
        val file = readAction { Locations.file(project, path) }
        val manager = manager()
        withContext(Dispatchers.EDT) {
            val bookmark = bookmark(file, line) ?: throw ToolException("the IDE cannot bookmark $path")
            val group = groupName?.let { manager.getGroup(it) ?: manager.addGroup(it, false) }
                ?: manager.defaultGroup
                ?: manager.addGroup(DEFAULT_GROUP, true)
            FocusKeeper.keeping(project) { group?.add(bookmark, BookmarkType.DEFAULT, description) }
        }
        if (reveal.mirroring) reveal.toolWindow(BOOKMARKS_WINDOW)
        return ToolResult.toon(
            buildJsonObject {
                put("path", path)
                put("line", line)
                put("group", groupName ?: DEFAULT_GROUP)
                put("added", true)
            },
        )
    }

    private fun bookmark(file: VirtualFile, line: Int): Bookmark? =
        LineBookmarkProvider.Util.find(project)?.createBookmark(file, line - 1) ?: manager().createBookmark(file)

    private suspend fun remove(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val line = args.int("line", 0)
        val file = readAction { Locations.file(project, path) }
        val manager = manager()
        val removed = withContext(Dispatchers.EDT) {
            val matching = manager.bookmarks.filter { bookmark ->
                bookmark is FileBookmark && bookmark.file == file && (line == 0 || (bookmark as? LineBookmark)?.line == line - 1)
            }
            matching.forEach(manager::remove)
            matching.size
        }
        return ToolResult.toon(
            buildJsonObject {
                put("path", path)
                put("line", line)
                put("removed", removed)
            },
        )
    }

    private suspend fun projectView(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val pane = args.optionalString("pane")
        val file = readAction { Locations.file(project, path) }
        val shown = FocusKeeper.keep(project) {
            val view = ProjectView.getInstance(project)
            if (pane != null) view.changeView(pane)
            view.select(null, file, false)
            view.currentViewId ?: ""
        }
        reveal.toolWindow(PROJECT_WINDOW)
        return ToolResult.toon(
            buildJsonObject {
                put("path", path)
                put("pane", shown)
                put("selected", true)
            },
        )
    }

    companion object {

        private const val DEFAULT_GROUP = "Claude"
        private const val BOOKMARKS_WINDOW = "Bookmarks"
        private const val PROJECT_WINDOW = "Project"

        private val PATH = Param("path", "File path, absolute or relative to the project root")

        val BOOKMARKS = ToolSpec(
            "bookmarks",
            "Every bookmark of the project as the Bookmarks window lists them: group, file, line, mnemonic and description.",
        )

        val BOOKMARK_ADD = ToolSpec(
            "bookmark_add",
            "Adds a bookmark on a file, or on a line of it, to a group (created when new; default: the IDE's default group), " +
                "with an optional description; the Bookmarks window is shown.",
            listOf(
                PATH,
                Param("line", "1-based line to bookmark (default: the file itself)", type = "integer", required = false),
                Param("group", "Bookmark group name (default: the default group)", required = false),
                Param("description", "Text shown beside the bookmark", required = false),
            ),
            mutates = true,
        )

        val BOOKMARK_REMOVE = ToolSpec(
            "bookmark_remove",
            "Removes the bookmarks on a file, or only the one on a line; returns how many went.",
            listOf(PATH, Param("line", "1-based line (default: every bookmark of the file)", type = "integer", required = false)),
            mutates = true,
        )

        val PROJECT_VIEW = ToolSpec(
            "project_view",
            "Selects a file in the Project tool window, switching to a pane (ProjectPane, PackagesPane, Scope…) when given, " +
                "without taking the focus; returns the pane shown.",
            listOf(PATH, Param("pane", "The project view pane id (default: the current one)", required = false)),
        )
    }
}
