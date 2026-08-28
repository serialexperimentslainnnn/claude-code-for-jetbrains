@file:OptIn(ExperimentalSerializationApi::class)

package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.ClaudeJson
import dev.lain.claudejb.protocol.ContextUsage
import dev.lain.claudejb.protocol.UsageReport
import dev.lain.claudejb.protocol.parseUsageReport
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

data class RewindResult(val canRewind: Boolean, val error: String?, val filesChanged: List<String>)

data class RemoteControlOutcome(
    val enabled: Boolean,
    val ok: Boolean,
    val sessionUrl: String?,
    val error: String?,
)

private val CLAUDE_SESSION_URL = Regex("""https://claude\.ai/[A-Za-z0-9./_#?=&-]+""")

internal fun sessionUrlIn(payload: JsonObject?): String? =
    payload?.let { CLAUDE_SESSION_URL.find(it.toString())?.value }

@Serializable
data class PlanInfo(
    val exists: Boolean? = null,
    val content: String = "",
    val path: String? = null,
)

@Serializable
data class WorkspaceDiff(
    val stats: Stats = Stats(),
    @JsonNames("per_file_stats")
    val perFileStats: List<FileStats> = emptyList(),
    val hunks: List<FileHunks> = emptyList(),
    @JsonNames("skipped_large")
    val skippedLarge: List<String> = emptyList(),
    val restricted: List<String> = emptyList(),
    val source: Source = Source(),
) {

    @Serializable
    data class Stats(
        @JsonNames("files_count") val filesCount: Int = 0,
        @JsonNames("lines_added") val linesAdded: Int = 0,
        @JsonNames("lines_removed") val linesRemoved: Int = 0,
    )

    @Serializable
    data class FileStats(
        val path: String = "",
        val added: Int = 0,
        val removed: Int = 0,
        @JsonNames("is_binary") val isBinary: Boolean = false,
        @JsonNames("is_untracked") val isUntracked: Boolean = false,
    )

    @Serializable
    data class FileHunks(val path: String = "", val hunks: List<Hunk> = emptyList())

    @Serializable
    data class Hunk(
        @JsonNames("old_start") val oldStart: Int = 0,
        @JsonNames("old_lines") val oldLines: Int = 0,
        @JsonNames("new_start") val newStart: Int = 0,
        @JsonNames("new_lines") val newLines: Int = 0,
        val lines: List<String> = emptyList(),
    )

    @Serializable
    data class Source(
        val kind: String = "",
        @JsonNames("base_branch") val baseBranch: String? = null,
        @JsonNames("base_ref") val baseRef: String? = null,
    )

    data class File(val stats: FileStats, val hunks: List<Hunk>, val withheld: Boolean)

    val files: List<File>
        get() {
            val byPath = hunks.associateBy({ it.path }, { it.hunks })
            val silent = (skippedLarge + restricted).toSet()
            return perFileStats.map { File(it, byPath[it.path].orEmpty(), it.path in silent) }
        }

    val baseLabel: String
        get() = when {
            source.kind == "branch" -> source.baseRef?.takeIf { it.isNotBlank() }?.let { "Base ($it)" } ?: "Base"
            else -> "HEAD"
        }
}

class Ask<T>(
    val subtype: String,
    val params: JsonObjectBuilder.() -> Unit = {},
    val decode: (JsonObject?) -> T?,
)

object Asks {

    val CONTEXT_USAGE = Ask("get_context_usage") { payload ->
        payload
            ?.let { runCatching { ClaudeJson.decodeFromJsonElement(ContextUsage.serializer(), it) }.getOrNull() }
            ?.takeIf { it.maxTokens > 0 || it.totalTokens > 0 || it.categories.isNotEmpty() }
    }

    val USAGE = Ask("get_usage") { parseUsageReport(it) }

    val SESSION_COST = Ask("get_session_cost") { it }

    val MCP_STATUS = Ask("mcp_status") { it }

    val SETTINGS = Ask("get_settings") { it }

    val WORKSPACE_DIFF = Ask("get_workspace_diff") { payload ->
        (payload?.get("diff") as? JsonObject)
            ?.let { runCatching { ClaudeJson.decodeFromJsonElement(WorkspaceDiff.serializer(), it) }.getOrNull() }
            ?.takeIf { it.perFileStats.isNotEmpty() || it.stats.filesCount > 0 }
    }

    val PLAN = Ask("get_plan") { payload ->
        payload
            ?.let { runCatching { ClaudeJson.decodeFromJsonElement(PlanInfo.serializer(), it) }.getOrNull() }
            ?.takeIf { it.exists != false && it.content.isNotBlank() }
    }

    val BINARY_VERSION = Ask("get_binary_version") { it }

    fun generateTitle(description: String, persist: Boolean = true) = Ask(
        subtype = "generate_session_title",
        params = {
            put("description", description)
            put("persist", persist)
        },
        decode = { payload ->
            (payload?.get("title") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        },
    )

    fun sideQuestion(question: String) = Ask(
        subtype = "side_question",
        params = { put("question", question) },
        decode = { payload ->
            (payload?.get("response") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        },
    )

    fun remoteControl(enabled: Boolean) = Ask(
        subtype = "remote_control",
        params = { put("enabled", enabled) },
        decode = { it },
    )

    fun rewind(userMessageId: String, dryRun: Boolean) = Ask(
        subtype = "rewind_files",
        params = {
            put("user_message_id", userMessageId)
            put("dry_run", dryRun)
        },
        decode = { payload ->
            payload?.let {
                RewindResult(
                    canRewind = (it["canRewind"] ?: it["can_rewind"])?.let { e -> (e as? JsonPrimitive)?.booleanOrNull } ?: false,
                    error = ((it["error"] ?: it["message"]) as? JsonPrimitive)?.contentOrNull,
                    filesChanged = ((it["filesChanged"] ?: it["files_changed"]) as? JsonArray)
                        ?.mapNotNull { e -> (e as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                )
            }
        },
    )
}
