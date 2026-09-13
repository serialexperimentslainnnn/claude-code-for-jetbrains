package dev.lain.claudejb.model.session.agents

import dev.lain.claudejb.model.session.history.SessionTranscriptReader
import dev.lain.claudejb.model.session.transcript.EntryDTO
import dev.lain.claudejb.model.settings.WorkloadWindow
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

enum class AgentStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    STOPPED,
    ;

    companion object {
        fun parse(status: String): AgentStatus = when (status.lowercase()) {
            "completed", "complete", "done", "finished", "success", "succeeded" -> COMPLETED
            "", "running", "in_progress", "in-progress", "started", "starting", "pending", "queued", "paused" -> RUNNING
            "stopped", "cancelled", "canceled", "interrupted", "aborted", "killed" -> STOPPED
            else -> FAILED
        }
    }
}

data class AgentNode(
    val meta: AgentMeta,
    val status: AgentStatus = AgentStatus.RUNNING,
    val entries: List<EntryDTO> = emptyList(),
    val completedAtMillis: Long? = null,
) {
    val agentId: String get() = meta.agentId
    val parentAgentId: String? get() = meta.parentAgentId
    val depth: Int get() = meta.spawnDepth

    val kindLabel: String get() = meta.workflowRun?.let { "Workflow agent" } ?: if (parentAgentId != null) "Subagent" else "Agent"
}

class AgentRegistry(
    private val subagentsDir: () -> Path?,
    private val onAdmitted: (agentId: String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val runStartedAtMillis: Long = WorkloadWindow.RUN_STARTED_AT,
) {
    private val observedToolUse: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val statusByToolUse = ConcurrentHashMap<String, AgentStatus>()

    private val completedAtByToolUse = ConcurrentHashMap<String, Long>()

    private val completedAtByAgent = ConcurrentHashMap<String, Long>()

    private val accountedRecordsByAgent = ConcurrentHashMap<String, Int>()

    private val preAdmitted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var snapshot: Map<String, AgentNode> = emptyMap()

    val nodes: Map<String, AgentNode> get() = snapshot

    fun children(parentId: String?): List<AgentNode> =
        snapshot.values.filter { it.parentAgentId == parentId }

    fun observeSpawn(toolUseId: String?) {
        if (!toolUseId.isNullOrBlank()) observedToolUse += toolUseId
    }

    fun observeSettled(toolUseId: String?, status: AgentStatus) {
        if (toolUseId.isNullOrBlank()) return
        statusByToolUse[toolUseId] = status
        if (status == AgentStatus.RUNNING) {
            completedAtByToolUse.remove(toolUseId)
            return
        }
        completedAtByToolUse.putIfAbsent(toolUseId, now())
        snapshot.values.forEach { node ->
            if (node.meta.toolUseId == toolUseId) accountedRecordsByAgent.remove(node.agentId)
        }
    }

    fun preAdmit(agentIds: Collection<String>) {
        preAdmitted += agentIds.map { AgentMeta.bareAgentId(it) }
    }

    @Volatile
    var restoring: Boolean = false
        private set

    fun markRestored() {
        restoring = true
    }

    fun scan(): List<String> {
        val dir = subagentsDir() ?: return emptyList()
        val metas = readMetas(dir)
        val admitted = admissibleIds(metas)
        val previous = snapshot
        val next = LinkedHashMap<String, AgentNode>()
        for (id in admitted.sortedWith(compareBy({ metas[it]?.spawnDepth ?: 1 }, { it }))) {
            val meta = metas[id] ?: continue
            val (entries, ending) = transcriptOf(meta.home(dir), id, previous[id])
            reopenIfGrown(meta, entries.size)
            val settled = settledStateOf(meta, next, ending)
            next[id] = AgentNode(
                meta = meta,
                status = settled.status,
                entries = entries,
                completedAtMillis = settled.completedAtMillis,
            )
        }
        snapshot = next
        cachedTranscripts.keys.retainAll(next.keys)
        val fresh = next.keys - previous.keys
        fresh.forEach(onAdmitted)
        return fresh.toList()
    }

    private fun transcriptOf(
        dir: Path,
        id: String,
        previousNode: AgentNode?,
    ): Pair<List<EntryDTO>, AgentEnding.Ending?> {
        val stamp = stampOf(dir.resolve(AgentMeta.transcriptFile(id)))
        val unchanged = cachedTranscripts[id]?.takeIf { it.stamp == stamp }
        if (unchanged != null && previousNode != null) return previousNode.entries to unchanged.ending
        val records = SessionTranscriptReader.parseRecords(readLines(dir, id))
        val ending = AgentEnding.of(records)
        cachedTranscripts[id] = CachedTranscript(stamp, ending)
        return SessionTranscriptReader.entriesOf(records) to ending
    }

    private data class FileStamp(val size: Long, val modifiedAtMillis: Long)

    private class CachedTranscript(val stamp: FileStamp, val ending: AgentEnding.Ending?)

    private val cachedTranscripts = ConcurrentHashMap<String, CachedTranscript>()

    private fun stampOf(file: Path): FileStamp = runCatching {
        val attrs = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes::class.java)
        FileStamp(attrs.size(), attrs.lastModifiedTime().toMillis())
    }.getOrDefault(missingFile)

    private val missingFile = FileStamp(-1, -1)

    private data class Settled(val status: AgentStatus, val completedAtMillis: Long?)

    private fun reopenIfGrown(meta: AgentMeta, count: Int) {
        val accounted = accountedRecordsByAgent.put(meta.agentId, count) ?: return
        if (count <= accounted) return
        meta.toolUseId?.let { id ->
            statusByToolUse.remove(id)
            completedAtByToolUse.remove(id)
        }
        completedAtByAgent.remove(meta.agentId)
    }

    private fun settledStateOf(
        meta: AgentMeta,
        resolved: Map<String, AgentNode>,
        ending: AgentEnding.Ending?,
    ): Settled {
        val observed = observedStateOf(meta, resolved, ending)
        if (observed.status == AgentStatus.RUNNING || observed.completedAtMillis != null) return observed
        return observed.copy(completedAtMillis = runStartedAtMillis)
    }

    private fun observedStateOf(
        meta: AgentMeta,
        resolved: Map<String, AgentNode>,
        ending: AgentEnding.Ending?,
    ): Settled {
        val streamStatus = meta.toolUseId?.let { statusByToolUse[it] }
        val parent = meta.parentAgentId?.let { resolved[it] }
        val live = meta.toolUseId?.let { it in observedToolUse } == true ||
            parent?.status == AgentStatus.RUNNING ||
            !restoring
        return when (ending) {
            AgentEnding.Ending.COMPLETED -> Settled(AgentStatus.COMPLETED, if (live) sealCompletion(meta) else null)
            AgentEnding.Ending.RESUMED -> Settled(AgentStatus.RUNNING, null)
            AgentEnding.Ending.ABORTED -> Settled(AgentStatus.STOPPED, if (live) sealCompletion(meta) else null)
            AgentEnding.Ending.UNFINISHED, null -> unfinishedFileState(meta, streamStatus, parent, live)
        }
    }

    private fun unfinishedFileState(
        meta: AgentMeta,
        streamStatus: AgentStatus?,
        parent: AgentNode?,
        live: Boolean,
    ): Settled {
        if (streamStatus != null && streamStatus != AgentStatus.RUNNING) {
            return Settled(streamStatus, meta.toolUseId?.let { completedAtByToolUse[it] })
        }
        return parent?.takeIf { it.status != AgentStatus.RUNNING }
            ?.let { Settled(it.status, it.completedAtMillis) }
            ?: Settled(if (live) AgentStatus.RUNNING else AgentStatus.STOPPED, null)
    }

    private fun sealCompletion(meta: AgentMeta): Long =
        meta.toolUseId?.let { completedAtByToolUse.computeIfAbsent(it) { now() } }
            ?: completedAtByAgent.computeIfAbsent(meta.agentId) { now() }

    private fun admissibleIds(metas: Map<String, AgentMeta>): Set<String> {
        val admitted = metas.values
            .filter {
                it.agentId in preAdmitted ||
                    (it.toolUseId != null && it.toolUseId in observedToolUse) ||
                    it.workflowRun != null ||
                    restoring
            }
            .mapTo(HashSet()) { it.agentId }
        var grew = true
        while (grew) {
            grew = false
            for (meta in metas.values) {
                val parent = meta.parentAgentId ?: continue
                if (meta.agentId !in admitted && parent in admitted) {
                    admitted += meta.agentId
                    grew = true
                }
            }
        }
        return admitted
    }

    private fun readMetas(dir: Path): Map<String, AgentMeta> =
        metasIn(dir, workflowRun = null) + workflowRuns(dir).flatMap { run -> metasIn(run, run.fileName.toString()).toList() }

    private fun workflowRuns(dir: Path): List<Path> = runCatching {
        Files.newDirectoryStream(dir.resolve(AgentMeta.WORKFLOWS_DIR)).use { stream -> stream.filter { Files.isDirectory(it) } }
    }.getOrDefault(emptyList())

    private fun metasIn(dir: Path, workflowRun: String?): Map<String, AgentMeta> = runCatching {
        Files.newDirectoryStream(dir, "*${AgentMeta.META_SUFFIX}").use { stream -> stream.mapNotNull { metaOf(it, workflowRun) }.toMap() }
    }.getOrDefault(emptyMap())

    private fun metaOf(path: Path, workflowRun: String?): Pair<String, AgentMeta>? {
        val id = AgentMeta.agentIdOfMetaFile(path.fileName.toString()) ?: return null
        val body = runCatching { Files.readString(path) }.getOrNull() ?: return null
        return AgentMeta.parse(id, body, workflowRun)?.let { id to it }
    }

    private fun readLines(dir: Path, agentId: String): List<String> =
        runCatching { Files.readAllLines(dir.resolve(AgentMeta.transcriptFile(agentId))) }.getOrDefault(emptyList())
}
