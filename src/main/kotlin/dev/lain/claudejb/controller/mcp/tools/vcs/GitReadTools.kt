package dev.lain.claudejb.controller.mcp.tools.vcs

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import dev.lain.claudejb.controller.git.GitHistoryService
import dev.lain.claudejb.controller.mcp.Reveal
import dev.lain.claudejb.model.git.GitCommitInfo
import dev.lain.claudejb.model.git.GitLogScope
import dev.lain.claudejb.model.git.GitRefInfo
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.coroutines.resume

internal class GitReadTools(
    private val project: Project,
    private val reveal: Reveal,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val history: GitHistoryService get() = project.service()

    fun domain(): ToolDomain = ToolDomain(
        "git",
        "Git as the IDE sees it: status, log, diff and branches, read-only",
        listOf(
            Tool(GIT_STATUS, ::status),
            Tool(GIT_LOG) { ToolResult.toon(Batch.run(it, Batch.HASHES, ::logOne)) },
            Tool(GIT_DIFF) { ToolResult.toon(Batch.run(it, Batch.PATHS, ::diffOne)) },
            Tool(GIT_BRANCHES, ::branches),
        ),
    )

    private suspend fun status(args: ToolArgs): ToolResult {
        val max = args.int("max", DEFAULT_STATUS_MAX)
        val root = root()
        awaitChangeLists()
        val manager = ChangeListManager.getInstance(project)
        val changed = manager.allChanges.map { changeRow(root, it) }
        val unversioned = manager.unversionedFilesPaths.map { row(VcsPaths.relative(root, it), UNVERSIONED) }
        val rows = (changed + unversioned).sortedBy { it.getValue("path").toString() }
        val topology = withContext(io) { history.branchTopology() }
        val conflicts = withContext(io) { history.hasConflicts() }
        return ToolResult.toon(
            buildJsonObject {
                put("root", root)
                put("branch", history.currentBranch() ?: "")
                put("head", history.headRevision() ?: "")
                put("upstream", topology.upstream ?: "")
                put("ahead", topology.ahead ?: 0)
                put("behind", topology.behind ?: 0)
                put("conflicts", conflicts)
                put("roots", buildJsonArray { history.repositoryRoots().forEach { add(JsonPrimitive(it)) } })
                put("count", rows.size)
                put("truncated", rows.size > max)
                put("changes", buildJsonArray { rows.take(max).forEach { add(it) } })
            },
        )
    }

    private suspend fun logOne(args: ToolArgs): JsonObject {
        val max = args.int("max", DEFAULT_LOG_MAX)
        val hash = args.optionalString("hash")
        val scope = if (args.boolean("all_branches", false)) GitLogScope.EVERY_LINE_OF_DEVELOPMENT else GitLogScope.CURRENT_BRANCH
        root()
        if (hash != null) return commitOne(hash)
        val commits = withContext(io) { history.recentCommits(max + 1, scope) }
        return buildJsonObject {
            put("count", commits.size.coerceAtMost(max))
            put("truncated", commits.size > max)
            put("commits", buildJsonArray { commits.take(max).forEach { add(commitRow(it)) } })
        }
    }

    private suspend fun commitOne(hash: String): JsonObject {
        if (!HASH.matches(hash)) throw ToolException("hash must be $MIN_HASH_LENGTH to $MAX_HASH_LENGTH hexadecimal characters")
        val commit = withContext(io) { history.commit(hash) } ?: throw ToolException("no commit $hash in this repository")
        if (reveal.mirroring) reveal.commit(hash)
        return buildJsonObject {
            commitRow(commit).forEach { (key, value) -> put(key, value) }
            put("paths", buildJsonArray { commit.changedPaths.forEach { add(JsonPrimitive(it)) } })
        }
    }

    private suspend fun diffOne(args: ToolArgs): JsonObject {
        val path = args.optionalString("path")
        val maxLines = args.int("max_lines", DEFAULT_DIFF_LINES)
        root()
        awaitChangeLists()
        val patch = withContext(io) { WorkingTreePatch.unified(project, path) }
        val lines = patch.text.lines()
        return buildJsonObject {
            put("path", path ?: "")
            put("files", patch.files)
            put("lines", lines.size)
            put("truncated", lines.size > maxLines)
            put("diff", lines.take(maxLines).joinToString("\n"))
        }
    }

    private suspend fun branches(args: ToolArgs): ToolResult {
        val max = args.int("max", DEFAULT_BRANCHES_MAX)
        root()
        val refs = withContext(io) { history.refs() }
        return ToolResult.toon(
            buildJsonObject {
                put("current", history.currentBranch() ?: "")
                put("count", refs.size)
                put("truncated", refs.size > max)
                put("branches", buildJsonArray { refs.take(max).forEach { add(refRow(it)) } })
            },
        )
    }

    private fun root(): String = history.primaryRepositoryRoot() ?: throw ToolException("this project is not a Git working copy")

    private suspend fun awaitChangeLists() = suspendCancellableCoroutine { continuation ->
        ChangeListManager.getInstance(project).invokeAfterUpdate(false) { continuation.resume(Unit) }
    }

    private fun changeRow(root: String, change: Change): JsonObject {
        val file = (change.afterRevision ?: change.beforeRevision)?.file
        return row(file?.let { VcsPaths.relative(root, it) } ?: "", change.type.name)
    }

    private fun row(path: String, type: String): JsonObject = buildJsonObject {
        put("path", path)
        put("type", type)
    }

    private fun commitRow(commit: GitCommitInfo): JsonObject = commitRowOf(commit)

    private fun refRow(ref: GitRefInfo): JsonObject = buildJsonObject {
        put("name", ref.name)
        put("kind", ref.kind.wire)
        put("hash", ref.hash)
        put("current", ref.current)
    }

    companion object {

        fun commitRowOf(commit: GitCommitInfo): JsonObject = buildJsonObject {
            put("hash", commit.hash)
            put("subject", commit.subject)
            put("author", commit.authorName)
            put("date", Instant.ofEpochMilli(commit.authoredAtMillis).toString())
            put("files", commit.changedPaths.size)
        }

        private const val DEFAULT_STATUS_MAX = 200
        private const val DEFAULT_LOG_MAX = 20
        private const val DEFAULT_DIFF_LINES = 400
        private const val DEFAULT_BRANCHES_MAX = 100
        private const val UNVERSIONED = "UNVERSIONED"
        private const val MIN_HASH_LENGTH = 4
        private const val MAX_HASH_LENGTH = 64
        private val HASH = Regex("[0-9a-fA-F]{$MIN_HASH_LENGTH,$MAX_HASH_LENGTH}")

        val GIT_STATUS = ToolSpec(
            "git_status",
            "The working tree as the IDE's Changes view sees it: branch, HEAD, upstream with ahead/behind, conflicts, and every " +
                "changed or unversioned path with its type (MODIFICATION, NEW, DELETED, MOVED, UNVERSIONED). Use it before " +
                "staging or committing.",
            listOf(Param("max", "Maximum changes to return (default $DEFAULT_STATUS_MAX)", type = "integer", required = false)),
        )

        val GIT_LOG = ToolSpec(
            "git_log",
            "Recent commits of the current branch, newest first, with hash, subject, author, ISO-8601 date and the number of " +
                "files each touched; with hash or hashes, those commits with the paths each changed. Pass a hash to " +
                "vcs_open(view=log) to show one in the IDE's Git log.",
            listOf(
                Param("hash", "One commit to describe, 4 to 64 hex characters (default: the recent commits)", required = false),
                Batch.param(Batch.HASHES, "Several commits at once, one result per hash"),
                Param("max", "Maximum commits to return (default $DEFAULT_LOG_MAX)", type = "integer", required = false),
                Param("all_branches", "true to include every branch, remote and tag (default false)", type = "boolean", required = false),
            ),
        )

        val GIT_DIFF = ToolSpec(
            "git_diff",
            "The unified diff of the uncommitted changes, as the IDE's Create Patch produces it: the whole tree, or one file " +
                "or directory. Use it to review before committing; for a commit's diff use vcs_open(view=log, hash).",
            listOf(
                Param("path", "File or directory, absolute or relative to the project root (default: whole tree)", required = false),
                Batch.paths("one diff each"),
                Param("max_lines", "Maximum diff lines to return (default $DEFAULT_DIFF_LINES)", type = "integer", required = false),
            ),
        )

        val GIT_BRANCHES = ToolSpec(
            "git_branches",
            "Every local and remote branch with the commit it points at, the current one first; a detached HEAD is listed as " +
                "kind head.",
            listOf(Param("max", "Maximum branches to return (default $DEFAULT_BRANCHES_MAX)", type = "integer", required = false)),
        )
    }
}
