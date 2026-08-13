package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.session.EntryDTO
import dev.lain.claudejb.session.TranscriptEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The transcript half of the outbound (Kotlin → JS) payloads: one row, a coalesced batch of rows, an agent's
 * reconstructed transcript, and the jump-to-code answer for a settled row.
 *
 * **Pure**, exactly like [JcefBridge], which parses the traffic going the other way: no IDE or browser state,
 * so the row shapes are unit-testable without a live Chromium. All escaping is handled by
 * kotlinx-serialization's [JsonObject.toString], so arbitrary model text crosses the boundary safely.
 */
object JcefTranscriptPayload {

    /**
     * One transcript row as the frontend's entry shape:
     * `{id, order, speaker, text, meta?, toolUseId?, parent?, state, elapsed}`. [order] is the row's current
     * index in the transcript; the frontend upserts by `id` and repositions the row to `order`, so a coalesced
     * batch can carry just the changed rows yet still land them in the right place.
     */
    fun entryJson(e: TranscriptEntry, order: Int): JsonObject = buildJsonObject {
        put("id", e.id)
        put("order", order)
        put("speaker", e.speaker.name)
        put("text", e.text)
        e.meta?.let { put("meta", it) }
        // A label that only became knowable after the row existed — an Agent card gaining the description of
        // what its agent is actually doing. The frontend prefers it over `meta`, which stays the tool's name.
        e.toolTitle?.let { put("title", it) }
        e.toolUseId?.let { put("toolUseId", it) }
        e.parentToolUseId?.let { put("parent", it) }
        // Project-relative file for a file tool → the frontend renders the card label as a jump-to-code link.
        e.filePath?.let { put("filePath", it) }
        // The raw command text for a command call (Bash, PowerShell, MCP…) → the frontend renders it as its
        // own copyable code block in the tool card, instead of plain text in the collapsed header.
        e.commandText?.let { put("command", it) }
        put("state", e.toolState.name)
        put("elapsed", e.elapsedSeconds)
        // A completed Edit/Write/MultiEdit card is reviewable: the frontend shows a "View diff"
        // button that opens the native diff from the captured pre-write snapshot (by tool_use_id).
        if (e.speaker.name == "TOOL" && e.toolUseId != null && e.meta in REVIEWABLE_TOOLS) {
            put("reviewable", true)
        }
    }

    /** Tools whose edits we can reconstruct a diff for — mirrors `DiffPresenter.REVIEWABLE_TOOLS`. */
    private val REVIEWABLE_TOOLS = setOf("Edit", "Write", "MultiEdit")

    /** A batch of `(row, order)` for one `cc.batch([...])` frame (the JS upserts each by id). JSON array literal. */
    fun batchJson(items: List<Pair<TranscriptEntry, Int>>): String =
        JsonArray(items.map { (e, order) -> entryJson(e, order) }).toString()

    /**
     * The same row shape, built from a **reconstructed** entry rather than a live one.
     *
     * An agent's transcript is read back from the binary's own per-agent file (as is a restored session's),
     * so it arrives as [dev.lain.claudejb.session.EntryDTO] with no live tool state and no row ids. Ids are
     * synthesised from the position, which is all the frontend needs — it upserts by id and repositions to
     * `order`, and a reconstructed transcript is replaced wholesale rather than patched row by row.
     *
     * Tool rows are marked FINISHED: whatever the agent was doing when it wrote that file, it is not doing
     * it now in a way this row can track, and a card left spinning forever is a lie the UI tells by omission.
     */
    fun agentBatchJson(
        entries: List<EntryDTO>,
        titles: Map<String, String> = emptyMap(),
        running: Set<String> = emptySet(),
        expanded: Boolean = false,
        /** Whether the agent this transcript belongs to is still working — see the `inFlight` branch below. */
        ownerRunning: Boolean = false,
    ): String =
        JsonArray(
            entries.mapIndexed { index, dto ->
                buildJsonObject {
                    put("id", index.toLong())
                    put("order", index)
                    put("speaker", dto.speaker)
                    put("text", dto.text)
                    dto.meta?.let { put("meta", it) }
                    // The same label the live path gives an Agent card, so a card inside an agent's own
                    // transcript reads `Agent (Inventory of dependencies)` too — it was only ever applied to
                    // the chat's rows, which is exactly where the user does NOT need it most.
                    dto.toolUseId?.let { id -> titles[id]?.let { put("title", it) } }
                    dto.toolUseId?.let { put("toolUseId", it) }
                    dto.filePath?.let { put("filePath", it) }
                    dto.commandText?.let { put("command", it) }
                    put("state", agentRowState(dto, running, ownerRunning))
                    // A background task's view is nothing BUT its command and its output: shipping it
                    // collapsed means the one thing you opened the tab for is behind a click.
                    if (expanded) put("open", true)
                    put("elapsed", 0)
                    if (dto.speaker == "TOOL" && dto.toolUseId != null && dto.meta in REVIEWABLE_TOOLS) {
                        put("reviewable", true)
                    }
                }
            },
        ).toString()

    /**
     * RUNNING when the call has no result yet ([EntryDTO.inFlight]) or when it IS an agent that is still
     * working. Marking everything FINISHED is what made a live card sit there green and still: a Bash the
     * agent was running right now looked exactly like one that had ended half an hour ago.
     */
    private fun agentRowState(dto: EntryDTO, running: Set<String>, ownerRunning: Boolean): String = when {
        // A failure outranks everything: it is the one state you must not miss.
        dto.failed -> "ERROR"

        dto.toolUseId in running -> "RUNNING"

        // A call with no result is only IN FLIGHT while something could still return it. In a transcript
        // whose owner has stopped, it was cut off — cancelled, not running.
        dto.inFlight -> if (ownerRunning) "RUNNING" else "ERROR"

        else -> "FINISHED"
    }

    /**
     * The answer to a `resolveLinks` request: `{ rowId, links:[{ token, path, line? }] }`. Only tokens the host
     * could actually resolve appear — the frontend links exactly those and leaves the rest as plain text.
     */
    fun linksJson(rowId: Long, resolved: List<dev.lain.claudejb.ui.LinkResolver.Resolved>): String =
        buildJsonObject {
            put("rowId", rowId)
            put(
                "links",
                buildJsonArray {
                    resolved.forEach { r ->
                        add(
                            buildJsonObject {
                                put("token", r.token)
                                put("path", r.path)
                                r.line?.let { put("line", it) }
                            },
                        )
                    }
                },
            )
        }.toString()
}
