package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.session.EntryDTO
import dev.lain.claudejb.session.TranscriptEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object JcefTranscriptPayload {

    fun entryJson(e: TranscriptEntry, order: Int): JsonObject = buildJsonObject {
        put("id", e.id)
        put("order", order)
        put("speaker", e.speaker.name)
        put("text", e.text)
        e.meta?.let { put("meta", it) }
        e.toolTitle?.let { put("title", it) }
        e.toolUseId?.let { put("toolUseId", it) }
        e.parentToolUseId?.let { put("parent", it) }
        e.filePath?.let { put("filePath", it) }
        e.commandText?.let { put("command", it) }
        e.messageText?.let { put("message", it) }
        e.blockedRule?.let { rule ->
            put("blockedRule", rule)
            // Whether adding this command to a whitelist warns first. The page needs it up front, because the
            // dialog is a host dialog and the link must not promise a silent add it is not going to make.
            put("blockedRuleWarns", SecurityRule.from(rule)?.whitelistable == false)
        }
        e.bypassedRule?.let { put("bypassedRule", it) }
        put("state", e.toolState.name)
        put("elapsed", e.elapsedSeconds)
        if (e.speaker.name == "TOOL" && e.toolUseId != null && e.meta in REVIEWABLE_TOOLS) {
            put("reviewable", true)
        }
    }

    private val REVIEWABLE_TOOLS = setOf("Edit", "Write", "MultiEdit")

    fun batchJson(items: List<Pair<TranscriptEntry, Int>>): String =
        JsonArray(items.map { (e, order) -> entryJson(e, order) }).toString()

    fun agentBatchJson(
        entries: List<EntryDTO>,
        titles: Map<String, String> = emptyMap(),
        running: Set<String> = emptySet(),
        expanded: Boolean = false,
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
                    dto.toolUseId?.let { id -> titles[id]?.let { put("title", it) } }
                    dto.toolUseId?.let { put("toolUseId", it) }
                    dto.filePath?.let { put("filePath", it) }
                    dto.commandText?.let { put("command", it) }
                    dto.messageText?.let { put("message", it) }
                    put("state", agentRowState(dto, running, ownerRunning))
                    if (expanded) put("open", true)
                    put("elapsed", 0)
                    if (dto.speaker == "TOOL" && dto.toolUseId != null && dto.meta in REVIEWABLE_TOOLS) {
                        put("reviewable", true)
                    }
                }
            },
        ).toString()

    private fun agentRowState(dto: EntryDTO, running: Set<String>, ownerRunning: Boolean): String = when {
        dto.failed -> "ERROR"
        dto.toolUseId in running -> "RUNNING"
        dto.inFlight -> if (ownerRunning) "RUNNING" else "ERROR"
        else -> "FINISHED"
    }

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
