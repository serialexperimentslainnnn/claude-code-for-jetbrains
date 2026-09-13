package dev.lain.claudejb.controller.mcp.tools.vcs

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.patch.PatchWriter
import com.intellij.openapi.vcs.changes.shelf.ShelveChangesManager
import com.intellij.openapi.vcs.changes.shelf.ShelvedChangeList
import com.intellij.openapi.vcs.changes.ui.RollbackWorker
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.platform.ide.progress.withBackgroundProgress
import dev.lain.claudejb.controller.mcp.FocusKeeper
import dev.lain.claudejb.controller.mcp.IdeActions
import dev.lain.claudejb.controller.mcp.Reveal
import dev.lain.claudejb.model.mcp.Batch
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

internal class ChangesTools(
    private val project: Project,
    private val actions: IdeActions,
    private val reveal: Reveal,
    private val git: GitCommands = GitCommands(project),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    fun domain(): ToolDomain = ToolDomain(
        "changes",
        "The uncommitted work as the Git menu and the Commit window handle it: stash, shelve, patches and rollback",
        listOf(Tool(STASH, ::stash), Tool(SHELVE, ::shelve), Tool(PATCH, ::patch), Tool(ROLLBACK, ::rollback)),
    )

    private suspend fun stash(args: ToolArgs): ToolResult {
        val action = args.optionalString("action") ?: "list"
        val message = args.optionalString("message")
        val output = withBackgroundProgress(project, "Claude: git stash $action", cancellable = true) {
            withContext(io) { git.stash(action, message) }
        }
        val listed = if (action == "list") output else withContext(io) { git.stash("list", null) }
        if (reveal.mirroring && action != "list") reveal.toolWindow(ToolWindowId.COMMIT)
        return ToolResult.toon(
            buildJsonObject {
                put("action", action)
                put("output", output.joinToString("\n").take(OUTPUT_CHARS))
                put("stashes", buildJsonArray { listed.forEach { add(JsonPrimitive(it)) } })
            },
        )
    }

    private suspend fun shelve(args: ToolArgs): ToolResult {
        val action = args.optionalString("action") ?: "list"
        val name = args.optionalString("name")
        val manager = ShelveChangesManager.getInstance(project)
        when (action) {
            "list" -> Unit

            "shelve" -> withContext(io) { vcs { manager.shelveChanges(changes(args.strings("paths")), name ?: DEFAULT_SHELF, true) } }

            "unshelve" -> withContext(Dispatchers.EDT) {
                val list = shelf(manager, name ?: throw ToolException("action=unshelve needs name"))
                FocusKeeper.keeping(project) { manager.unshelveChangeList(list, null, null, null, true) }
            }

            else -> throw ToolException("action must be list, shelve or unshelve")
        }
        if (reveal.mirroring && action != "list") reveal.toolWindow(ToolWindowId.COMMIT)
        val lists = manager.shelvedChangeLists
        return ToolResult.toon(
            buildJsonObject {
                put("action", action)
                put("count", lists.size)
                put(
                    "shelves",
                    buildJsonArray {
                        lists.forEach { list ->
                            add(
                                buildJsonObject {
                                    put("name", list.description)
                                    put("date", list.date.toInstant().toString())
                                    put("files", (list.changes?.size ?: 0) + list.binaryFiles.size)
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    private fun shelf(manager: ShelveChangesManager, name: String): ShelvedChangeList =
        manager.shelvedChangeLists.firstOrNull { it.description == name }
            ?: throw ToolException("no shelf named $name; action=list shows them")

    private suspend fun patch(args: ToolArgs): ToolResult {
        val action = args.string("action")
        val path = args.optionalString("path")
        when (action) {
            "create" -> withContext(io) { create(path ?: throw ToolException("action=create needs path"), args.strings("paths")) }
            "apply" -> actions.dispatch(APPLY_PATCH)
            else -> throw ToolException("action must be create or apply")
        }
        return ToolResult.toon(
            buildJsonObject {
                put("action", action)
                put("path", path ?: "")
                put("done", true)
            },
        )
    }

    private fun create(path: String, paths: List<String>) {
        val base = VcsPaths.base(project)
        val target = base.resolve(path).normalize()
        val changes = changes(paths)
        if (changes.isEmpty()) throw ToolException("there are no changes to put in a patch")
        vcs {
            val patches = IdeaTextPatchBuilder.buildPatch(project, changes, base, false)
            try {
                PatchWriter.writePatches(project, target, base, patches, null)
            } catch (e: IOException) {
                throw ToolException("cannot write $path: ${e.message}", e)
            }
        }
    }

    private suspend fun rollback(args: ToolArgs): ToolResult {
        val paths = args.strings("paths")
        if (paths.isEmpty()) throw ToolException("paths must name at least one changed file")
        withContext(Dispatchers.EDT) { FileDocumentManager.getInstance().saveAllDocuments() }
        val changes = changes(paths)
        if (changes.isEmpty()) throw ToolException("none of the paths has an uncommitted change")
        rollBack(changes)
        return ToolResult.toon(
            buildJsonObject {
                put("count", changes.size)
                put("paths", buildJsonArray { paths.forEach { add(JsonPrimitive(it)) } })
            },
        )
    }

    private suspend fun rollBack(changes: List<Change>) {
        val refreshed = CompletableDeferred<Unit>()
        withContext(Dispatchers.EDT) {
            FocusKeeper.keeping(project) {
                RollbackWorker(project, "Claude: rollback", false).doRollback(changes, false, { refreshed.complete(Unit) }, null)
            }
        }
        withTimeoutOrNull(ROLLBACK_TIMEOUT_MILLIS) { refreshed.await() }
            ?: throw ToolException("the rollback did not finish within ${ROLLBACK_TIMEOUT_MILLIS / MILLIS} s; check the Commit window")
    }

    private fun changes(paths: List<String>): List<Change> {
        val all = ChangeListManager.getInstance(project).allChanges
        if (paths.isEmpty()) return all.toList()
        val wanted = paths.map { VcsPaths.filePath(project, it).path }.toSet()
        return all.filter { change -> (change.afterRevision ?: change.beforeRevision)?.file?.path in wanted }
    }

    private fun <T> vcs(block: () -> T): T = try {
        block()
    } catch (e: VcsException) {
        throw ToolException(e.message.ifBlank { "the VCS refused" }, e)
    } catch (e: IOException) {
        throw ToolException(e.message ?: "the VCS refused", e)
    }

    companion object {

        private const val OUTPUT_CHARS = 2_000
        private const val ROLLBACK_TIMEOUT_MILLIS = 30_000L
        private const val MILLIS = 1000L
        private const val DEFAULT_SHELF = "Claude"
        private const val APPLY_PATCH = "ChangesView.ApplyPatch"

        private val PATHS = Batch.param(Batch.PATHS, "Only these changed files, all in one call (default: every uncommitted change)")

        val STASH = ToolSpec(
            "stash",
            "git stash through the IDE's Git: save (with message), pop, apply, drop or list. Returns git's output and the " +
                "stash list after the action; the Commit window is shown.",
            listOf(
                Param("action", "list (default), save, pop, apply or drop", required = false),
                Param("message", "The stash message (save)", required = false),
            ),
            mutates = true,
        )

        val SHELVE = ToolSpec(
            "shelve",
            "The IDE's shelf: list the shelves, shelve the uncommitted changes (or only paths) under a name and roll them " +
                "back, or unshelve a shelf by name into the working tree, as Git ▸ Shelve and Unshelve do.",
            listOf(
                Param("action", "list (default), shelve or unshelve", required = false),
                Param("name", "The shelf name (shelve, unshelve)", required = false),
                PATHS,
            ),
            mutates = true,
        )

        val PATCH = ToolSpec(
            "patch",
            "create writes the uncommitted changes (or only paths) as a unified diff to path, as Git ▸ Create Patch " +
                "does; apply opens the IDE's Apply Patch dialog for the user to pick a file and review it.",
            listOf(
                Param("action", "create or apply"),
                Param("path", "The patch file to write, relative to the project root (create)", required = false),
                PATHS,
            ),
            mutates = true,
        )

        val ROLLBACK = ToolSpec(
            "rollback",
            "Reverts the uncommitted changes of the given files to the last commit through the IDE's Rollback, with its " +
                "local-history label so it can be undone; paths go all in one call.",
            listOf(Batch.param(Batch.PATHS, "The changed files to roll back, all in one call")),
            mutates = true,
        )
    }
}
