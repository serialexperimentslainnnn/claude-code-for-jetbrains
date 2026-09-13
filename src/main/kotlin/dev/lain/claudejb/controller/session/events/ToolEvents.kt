package dev.lain.claudejb.controller.session.events

import dev.lain.claudejb.controller.mcp.ToolOutput
import dev.lain.claudejb.controller.mcp.ToolOutputListener
import dev.lain.claudejb.controller.session.ClaudeSession
import dev.lain.claudejb.model.diff.DiffPresenter
import dev.lain.claudejb.model.diff.EditSnapshot
import dev.lain.claudejb.model.mcp.Batch
import dev.lain.claudejb.model.mcp.OwnTools
import dev.lain.claudejb.model.permission.scan.ToolInputScanner
import dev.lain.claudejb.model.protocol.ClaudeEvent
import dev.lain.claudejb.model.session.agents.AgentStatus
import dev.lain.claudejb.model.session.transcript.CardPlaces
import dev.lain.claudejb.model.session.transcript.LiveLines
import dev.lain.claudejb.model.session.transcript.Speaker
import dev.lain.claudejb.model.session.transcript.ToolNaming
import dev.lain.claudejb.model.session.transcript.ToolState
import dev.lain.claudejb.model.session.transcript.TranscriptEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ToolEvents(
    private val s: ClaudeSession,
    private val edt: (() -> Unit) -> Unit,
    private val fireState: () -> Unit,
) {

    private class Item(val id: String, val args: JsonObject)

    private class Own(val call: OwnTools.Call, val items: List<Item>, val batch: Boolean)

    private val live = HashMap<String, Pair<TranscriptEntry, LiveLines>>()
    private val early = HashMap<String, LiveLines>()
    private val ownCalls = HashMap<String, Own>()

    init {
        s.project.messageBus.connect(s).subscribe(
            ToolOutput.TOPIC,
            ToolOutputListener { toolUseId, line -> edt { onLiveLine(toolUseId, line) } },
        )
    }

    private fun onLiveLine(toolUseId: String, line: String) {
        if (!s.transcript.knowsTool(toolUseId)) {
            early.getOrPut(toolUseId) { LiveLines() }.add(line)
            return
        }
        val (entry, ring) = live.getOrPut(toolUseId) {
            s.transcript.addToolOutput(toolUseId, "", meta = LIVE) to (early.remove(toolUseId) ?: LiveLines())
        }
        ring.add(line)
        s.transcript.replaceText(entry, ring.render())
    }

    private fun flushEarly(toolUseId: String) {
        val ring = early.remove(toolUseId) ?: return
        val entry = s.transcript.addToolOutput(toolUseId, ring.render(), meta = LIVE)
        live[toolUseId] = entry to ring
    }

    fun onToolUse(event: ClaudeEvent.ToolUse) = edt {
        if (event.parentToolUseId == null) s.reconciler.onMessageBoundary()
        val own = OwnTools.parse(event.name, event.input)
        if (own != null) {
            ownCalls[event.id] = ownUse(own, event)
            return@edt
        }
        s.transcript.add(
            Speaker.TOOL,
            ToolNaming.formatToolUse(event.name, event.input, s.project.basePath),
            meta = event.name,
            toolUseId = event.id,
            parentToolUseId = event.parentToolUseId,
            toolState = ToolState.LOADING,
            filePath = ToolNaming.toolFilePath(event.name, event.input, s.project.basePath),
            commandText = ToolInputScanner.commandText(event.input),
            messageText = ToolInputScanner.messageText(event.input),
            reviewable = event.name in DiffPresenter.REVIEWABLE_TOOLS,
        )
        flushEarly(event.id)
        if (event.name in DiffPresenter.REVIEWABLE_TOOLS) {
            s.diffs.captureForReview(event.name, event.input, event.id)
            s.prompts.bindTool(event.id)
        }
    }

    private fun ownUse(own: OwnTools.Call, event: ClaudeEvent.ToolUse): Own {
        val args = OwnTools.argsOf(event.input)
        val split = Batch.split(own.argument, args)
        val items = (split ?: listOf(args)).mapIndexed { index, itemArgs ->
            val id = if (split == null) event.id else Batch.itemId(event.id, index)
            val review = OwnTools.reviewAs(own, itemArgs, s.project.basePath)?.let(::asWrite)
            s.transcript.add(
                Speaker.TOOL,
                OwnTools.label(own, itemArgs),
                meta = event.name,
                toolUseId = id,
                parentToolUseId = event.parentToolUseId,
                toolState = ToolState.LOADING,
                filePath = OwnTools.path(itemArgs),
                messageText = if (review == null) OwnTools.argsToon(itemArgs) else null,
                reviewable = review != null,
            )
            flushEarly(id)
            review?.let { s.diffs.captureForReview(it.toolName, it.input, id) }
            Item(id, itemArgs)
        }
        return Own(own, items, split != null)
    }

    private fun asWrite(review: OwnTools.Review): OwnTools.Review? {
        if (review.toolName != OwnTools.INSERT) return review
        val path = DiffPresenter.filePathOf(review.input) ?: return null
        val before = DiffPresenter.readCurrent(path, s.project.basePath) ?: return null
        return OwnTools.asWrite(review, before)
    }

    fun onToolResult(event: ClaudeEvent.ToolResult) = edt {
        if (s.backgroundTaskRegistry.observe(event)) {
            s.poll.ensureOutputTail()
            fireState()
        }
        if (!event.isError) {
            s.diffs.refreshTouched()
            if (ToolNaming.mayHaveWrittenUnknownFiles(s.transcript.toolNameOf(event.toolUseId))) {
                s.diffs.refreshProjectTree()
            }
        }
        val own = ownCalls.remove(event.toolUseId)
        if (own != null) {
            ownResult(event, own)
            return@edt
        }
        settle(event.toolUseId, event.isError)
        val diff = s.diffs.onToolResult(event.toolUseId)?.let(::diffOf)
        if (diff != null) {
            s.transcript.addToolOutput(event.toolUseId, diff, meta = DIFF)
            return@edt
        }
        rawOutput(event.toolUseId, event.content.trim(), event.isError)
    }

    private fun settle(toolUseId: String, failed: Boolean) {
        live.remove(toolUseId)
        early.remove(toolUseId)
        if (s.runningAgents.nodes.values.none { it.meta.toolUseId == toolUseId }) {
            s.transcript.setToolState(toolUseId, if (failed) ToolState.ERROR else ToolState.FINISHED)
        }
    }

    private fun ownResult(event: ClaudeEvent.ToolResult, own: Own) {
        val text = event.content.trim()
        val decoded = if (event.isError) null else OwnTools.decodeResult(text) as? JsonObject
        val results: List<JsonObject?> = when {
            !own.batch -> listOf(decoded)
            else -> (decoded?.get("items") as? JsonArray)?.map { it as? JsonObject }.orEmpty()
        }
        own.items.forEachIndexed { index, item ->
            val result = results.getOrNull(index)
            val error = result?.let { errorOf(it) }
            settle(item.id, event.isError || result == null || error != null)
            val diff = s.diffs.onToolResult(item.id)?.let(::diffOf)
            when {
                result == null -> if (index == 0) rawOutput(item.id, text, event.isError)
                error != null -> s.transcript.addToolOutput(item.id, error, meta = ERROR)
                diff != null -> s.transcript.addToolOutput(item.id, diff, meta = DIFF)
                else -> ownOutput(own.call, item, result)
            }
        }
    }

    private fun ownOutput(call: OwnTools.Call, item: Item, result: JsonObject) {
        s.transcript.setToolPlaces(item.id, CardPlaces.of(call.argument ?: "", item.args, result))
        val read = if (OwnTools.isRead(call)) OwnTools.readText(result) else null
        if (read != null) {
            s.transcript.addToolOutput(item.id, read)
        } else {
            s.transcript.addToolOutput(item.id, result.toString(), meta = TOON)
        }
    }

    private fun rawOutput(toolUseId: String, text: String, failed: Boolean) {
        if (text.isBlank()) return
        val tags = buildList {
            if (s.transcript.isCommandCall(toolUseId)) add("command")
            if (failed) add(ERROR)
        }
        s.transcript.addToolOutput(toolUseId, text, meta = tags.joinToString(" ").ifBlank { null })
    }

    private fun diffOf(snap: EditSnapshot): String? = DiffPresenter.proposedContent(snap.toolName, snap.input, snap.beforeText)
        ?.let { DiffPresenter.unifiedDiff(snap.beforeText, it) }
        ?.takeIf { it.isNotBlank() }

    private fun errorOf(result: JsonObject): String? = (result[ERROR] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val TOON = "toon"
        const val LIVE = "live"
        const val DIFF = "diff"
        const val ERROR = "error"
    }

    fun labelAgentCards() {
        s.runningAgents.nodes.values.forEach { node ->
            val toolUseId = node.meta.toolUseId ?: return@forEach
            s.transcript.toolNameOf(toolUseId) ?: return@forEach
            s.transcript.setToolState(
                toolUseId,
                when (node.status) {
                    AgentStatus.RUNNING -> ToolState.RUNNING
                    AgentStatus.COMPLETED -> ToolState.FINISHED
                    else -> ToolState.ERROR
                },
            )
            val label = node.meta.description?.takeIf { it.isNotBlank() } ?: return@forEach
            s.transcript.setToolTitle(toolUseId, "${node.kindLabel} ($label)")
        }
    }
}
