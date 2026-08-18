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

/** Result of a `rewind_files` control request. */
data class RewindResult(val canRewind: Boolean, val error: String?, val filesChanged: List<String>)

/**
 * The session's plan-mode plan, as `get_plan` returns it.
 *
 * The wire shape is `{exists, content?, path?}` — the binary's own schema states content and path are
 * "present iff exists is true". [Asks.PLAN] therefore never hands out an instance that has no plan text:
 * `exists:false`, an absent `exists` with no content, or an empty body all decode to null, so a caller
 * holding a [PlanInfo] holds something it can actually render.
 */
@Serializable
data class PlanInfo(
    /**
     * The binary's own answer to "is there a plan". Nullable on purpose: ABSENT is not the same as `false`,
     * and only an explicit `false` is allowed to veto a body that did arrive.
     */
    val exists: Boolean? = null,
    /** Plan markdown. */
    val content: String = "",
    /** Absolute path of the plan file on the session filesystem. */
    val path: String? = null,
)

/**
 * The whole session's workspace diff, as `get_workspace_diff` returns it — one round-trip for the question
 * "show me everything that changed", which the per-edit diffs cannot answer.
 *
 * Field names are the binary's (camelCase), with the snake_case spelling accepted alongside each one:
 * the reply for `rewind_files` has arrived in both spellings at different versions, and a diff that silently
 * reports zero files because the key was the other one is indistinguishable from a clean tree.
 *
 * Caps are the binary's, not ours (5s git timeout, 50 files, 1 MB per file), and they are visible rather than
 * silent: [skippedLarge] and [restricted] name the files whose stats are present but whose hunks are not.
 */
@Serializable
data class WorkspaceDiff(
    val stats: Stats = Stats(),
    @JsonNames("per_file_stats")
    val perFileStats: List<FileStats> = emptyList(),
    val hunks: List<FileHunks> = emptyList(),
    /** Paths whose diff text exceeded the per-file or aggregate size cap: stats but no hunks. */
    @JsonNames("skipped_large")
    val skippedLarge: List<String> = emptyList(),
    /** Paths whose hunk content was withheld by read-permission rules: stats but no hunks. */
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

    /** One unified-diff hunk: the `@@` header's four numbers plus its ` `/`-`/`+`-prefixed lines. */
    @Serializable
    data class Hunk(
        @JsonNames("old_start") val oldStart: Int = 0,
        @JsonNames("old_lines") val oldLines: Int = 0,
        @JsonNames("new_start") val newStart: Int = 0,
        @JsonNames("new_lines") val newLines: Int = 0,
        val lines: List<String> = emptyList(),
    )

    /**
     * Which base the binary diffed against: `working-tree` (vs HEAD) or, when the tree is clean,
     * `branch` (vs the default branch's merge base). Either way the NEW side is the working tree.
     */
    @Serializable
    data class Source(
        val kind: String = "",
        @JsonNames("base_branch") val baseBranch: String? = null,
        @JsonNames("base_ref") val baseRef: String? = null,
    )

    /** One file's stats joined to its hunks — the per-file view a reviewer needs, keyed the way the wire keys it. */
    data class File(val stats: FileStats, val hunks: List<Hunk>, val withheld: Boolean)

    /**
     * [perFileStats] joined to [hunks] by path. The two arrays are separate on the wire and a path may appear
     * in the first without the second — untracked (git emits no hunks for it), too large, or withheld — so the
     * join is left-outer and records WHY the hunks are missing rather than dropping the file.
     */
    val files: List<File>
        get() {
            val byPath = hunks.associateBy({ it.path }, { it.hunks })
            val silent = (skippedLarge + restricted).toSet()
            return perFileStats.map { File(it, byPath[it.path].orEmpty(), it.path in silent) }
        }

    /** A short human label for the base, for the diff tab's left-hand title. */
    val baseLabel: String
        get() = when {
            source.kind == "branch" -> source.baseRef?.takeIf { it.isNotBlank() }?.let { "Base ($it)" } ?: "Base"
            else -> "HEAD"
        }
}

/**
 * ONE control request, declared: what to ask for, what to send with it, and how to read the answer.
 *
 * Each of these used to be written out three times over — a builder in `ControlProtocol`, a method that
 * repeated the same six lines of plumbing, and a decode expression buried in the middle of it. Declared,
 * a new request is one line and cannot forget the parts that matter (the not-running answer, the EDT hop,
 * the correlation id, the watchdog), because none of them are its business: [SessionQueries.ask] owns them.
 */
class Ask<T>(
    /** The protocol's own name for it — the `subtype` field of the control request. */
    val subtype: String,
    /** Extra fields this request carries, if any. */
    val params: JsonObjectBuilder.() -> Unit = {},
    /** Reads the reply. Receives null when the binary refused or the watchdog fired. */
    val decode: (JsonObject?) -> T?,
)

/**
 * The control requests the plugin sends, in one place.
 *
 * This is the catalogue to add to. A request that is not here is a request whose plumbing someone wrote by
 * hand, and the hand-written ones are where the not-running case and the EDT hop go missing.
 */
object Asks {

    /**
     * How much of the window the conversation is using, by category.
     *
     * A reply carrying NONE of these fields decodes to null, not to a zero-valued reading. Every field of
     * [ContextUsage] has a default and the decoding is lenient, so `{"totally":"unexpected"}` — a protocol
     * change, a truncated frame — used to decode "successfully" into all zeros, and the dashboard then drew a
     * context meter reading 0%. A card that omits itself when there is no data is the design; a card showing
     * a confident wrong number is worse than no card, because the user has no way to tell them apart.
     *
     * Deliberately permissive about WHICH field is present: any one of them means the binary answered about
     * something. Only the reply that says nothing at all is refused.
     */
    val CONTEXT_USAGE = Ask("get_context_usage") { payload ->
        payload
            ?.let { runCatching { ClaudeJson.decodeFromJsonElement(ContextUsage.serializer(), it) }.getOrNull() }
            ?.takeIf { it.maxTokens > 0 || it.totalTokens > 0 || it.categories.isNotEmpty() }
    }

    /** Every rate-limit window plus the extra-credit balance, in one round-trip. */
    val USAGE = Ask("get_usage") { parseUsageReport(it) }

    /** What this session has spent. The payload IS the answer. */
    val SESSION_COST = Ask("get_session_cost") { it }

    /** MCP servers and their health. */
    val MCP_STATUS = Ask("mcp_status") { it }

    /** Effective merged settings + per-source breakdown (diagnostics dialog). */
    val SETTINGS = Ask("get_settings") { it }

    /**
     * Everything this session changed on disk, in one round-trip — the question the per-edit diffs cannot
     * answer, and the one you ask before accepting a long autonomous run.
     *
     * The reply is `{"diff": …|null}`; `diff` is null when the directory is not a git repo or git is mid
     * merge/rebase/cherry-pick. Same rule as [CONTEXT_USAGE]: a diff that names no file at all and counts no
     * change decodes to **null**, not to an empty-but-successful reading, so the caller cannot open a review
     * of nothing and call it a clean tree.
     */
    val WORKSPACE_DIFF = Ask("get_workspace_diff") { payload ->
        (payload?.get("diff") as? JsonObject)
            ?.let { runCatching { ClaudeJson.decodeFromJsonElement(WorkspaceDiff.serializer(), it) }.getOrNull() }
            ?.takeIf { it.perFileStats.isNotEmpty() || it.stats.filesCount > 0 }
    }

    /**
     * The session's plan-mode plan, on demand — in the transcript the plan is one card you scroll past.
     *
     * `{"exists": false}` (and an absent `exists` with no body) decodes to null: the binary answers that
     * whenever no plan slug has been allocated, which is the common case, and a plan card rendering an empty
     * body is the "confident wrong answer" [CONTEXT_USAGE] exists to avoid. Only an explicit `false` vetoes a
     * body that did arrive — ABSENT is not the same as false.
     */
    val PLAN = Ask("get_plan") { payload ->
        payload
            ?.let { runCatching { ClaudeJson.decodeFromJsonElement(PlanInfo.serializer(), it) }.getOrNull() }
            ?.takeIf { it.exists != false && it.content.isNotBlank() }
    }

    /** The responder's CLI binary version (diagnostics dialog). */
    val BINARY_VERSION = Ask("get_binary_version") { it }

    /**
     * Asks the binary to NAME this conversation, and to keep the name.
     *
     * The binary generates session titles and always could; what it does not do is generate one unprompted
     * for a `--print` session — no `ai-title` line exists in any session file on this machine. It is a
     * request, and this is it: `{subtype:"generate_session_title", description, persist}` → `{title}`,
     * declared beside `side_question` in the SDK's own client. One round-trip on the session that is already
     * up: no second process, no credential, no model of our own.
     *
     * **[persist] defaults to true, and that is the whole storage design.** The binary writes the title into
     * its own session file, so it survives `--resume` and the next IDE start, [SessionTitleReader.pick] finds
     * it as an authored title, and nobody asks for it twice. The plugin invents no place to keep it and — as
     * everywhere else — writes nothing into the binary's files itself; letting the binary do it is the clean
     * exit, not a workaround.
     *
     * A blank title decodes to null: a caller that painted it would replace a usable fallback with an empty
     * tab, which is the one outcome worse than not asking.
     */
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

    /**
     * `/btw` — a question answered alongside the conversation, without becoming a turn in it.
     *
     * **The answer comes back HERE, in the control response, not on the message stream**, and that is why this
     * is a control request rather than another user line: output the binary does not label as the main run is
     * dropped by [TranscriptReconciler.belongsHere] — the same filter that keeps a subagent's blocks out of
     * this transcript — so a side answer fished out of the stream is a side answer nobody sees. It is also
     * what `system/control_request_progress` reports the progress OF; until this existed, nothing sent the
     * request it correlates to.
     *
     * The SDK's own client can attach a `history` array here. It is not sent: the question is asked inside a
     * live session that already holds the conversation, and the field's shape is declared nowhere in the
     * published `.d.ts` — sending a guess costs more than the nothing it buys.
     *
     * A null or blank `response` decodes to null; the caller says so rather than leaving the question with no
     * reply under it.
     */
    fun sideQuestion(question: String) = Ask(
        subtype = "side_question",
        params = { put("question", question) },
        decode = { payload ->
            (payload?.get("response") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        },
    )

    /**
     * Rewind tracked files to a turn anchor. With `dryRun` the binary only reports feasibility.
     *
     * Both spellings of every field are read: the binary has sent camelCase and snake_case for these at
     * different versions, and a rewind that silently reports "cannot" because the key was the other one is
     * indistinguishable from a rewind that genuinely cannot.
     */
    fun rewind(userMessageId: String, dryRun: Boolean) = Ask(
        subtype = "rewind_files",
        // SNAKE_CASE on the way out — that is what the binary accepts, and it is not symmetric with the
        // reply, which has been seen in both spellings (hence the two-key reads below).
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
