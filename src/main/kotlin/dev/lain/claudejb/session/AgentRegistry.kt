package dev.lain.claudejb.session

import java.nio.file.Files
import java.nio.file.Path

/** Lifecycle of one agent, as far as the plugin can honestly tell. */
enum class AgentStatus { RUNNING, COMPLETED, FAILED, STOPPED }

/**
 * One agent as the UI needs it: what the binary says about it ([meta]), how it ended ([status]) and its
 * reconstructed transcript ([entries]).
 */
data class AgentNode(
    val meta: AgentMeta,
    val status: AgentStatus = AgentStatus.RUNNING,
    val entries: List<EntryDTO> = emptyList(),
) {
    val agentId: String get() = meta.agentId
    val parentAgentId: String? get() = meta.parentAgentId
    val depth: Int get() = meta.spawnDepth
}

/**
 * The agents of one chat: which ones may be shown, their tree, their status and their transcripts.
 *
 * **Where the data comes from.** The binary already writes everything, one pair of files per subagent under
 * `<sessionId>/subagents/`: `agent-<id>.meta.json` (see [AgentMeta]) and `agent-<id>.jsonl`, the latter in the
 * very same format as a session transcript — so it is read with [SessionTranscriptReader.parseEntries], the
 * parser that already exists, and a restored agent and a live one go through exactly one code path. That is
 * deliberate: the duplicated-thinking bug of 4.0.4 came from having two.
 *
 * **What may be shown, and why it is not "whatever is in the directory".** A session id can be resumed from
 * the terminal, so that directory mixes agents this plugin spawned with agents it never saw — in one real
 * session, 84 of them. An agent is admitted only if either:
 *  - its `toolUseId` matches a Task call this plugin **observed** ([observeSpawn]), or
 *  - its `parentAgentId` is an already-admitted agent.
 *
 * The second rule is not a convenience: a nested agent is spawned *inside* another agent's turn, so its
 * `task_started` never reaches the main stream. Without inheriting admission, everything below depth 1 would
 * be invisible. Admissions are remembered by [PluginAgentIndex], which is what lets a past plugin session's
 * agents come back after a restart while a terminal-spawned one never appears.
 *
 * Threading: [scan] does blocking IO and must run off the EDT; [nodes] is a snapshot, safe to read anywhere.
 */
class AgentRegistry(
    private val subagentsDir: () -> Path?,
    private val onAdmitted: (agentId: String) -> Unit = {},
) {
    /** `tool_use_id`s of Task calls seen in this session — the seed of the admission rule. */
    private val observedToolUse = LinkedHashSet<String>()

    /** Terminal status per `tool_use_id`, from `task_notification`. Absent means still running. */
    private val statusByToolUse = HashMap<String, AgentStatus>()

    /** Agent ids admitted by an outside authority (the persisted index), so a restart keeps them. */
    private val preAdmitted = LinkedHashSet<String>()

    @Volatile
    private var snapshot: Map<String, AgentNode> = emptyMap()

    /** Current agents, keyed by agent id. Ordered by depth then discovery, so parents precede children. */
    val nodes: Map<String, AgentNode> get() = snapshot

    /** Children of [parentId] (null = the agents of the main turn), in stable order. */
    fun children(parentId: String?): List<AgentNode> =
        snapshot.values.filter { it.parentAgentId == parentId }

    /** A Task call was seen in this session: any agent whose sidecar names it becomes ours. */
    fun observeSpawn(toolUseId: String?) {
        if (!toolUseId.isNullOrBlank()) observedToolUse += toolUseId
    }

    /** A `task_notification` settled a Task call — the agent's tab keeps its transcript, marked with this. */
    fun observeSettled(toolUseId: String?, status: AgentStatus) {
        if (!toolUseId.isNullOrBlank()) statusByToolUse[toolUseId] = status
    }

    /** Re-admits agents recorded by a previous plugin run (see [PluginAgentIndex]). */
    fun preAdmit(agentIds: Collection<String>) {
        preAdmitted += agentIds
    }

    /**
     * Re-reads the subagents directory and rebuilds the snapshot. Blocking IO — call off the EDT.
     *
     * Returns the agent ids admitted for the FIRST time in this call, so the caller can raise a tab, blink it
     * and notify without having to diff the snapshot itself.
     */
    fun scan(): List<String> {
        val dir = subagentsDir() ?: return emptyList()
        val metas = readMetas(dir)
        val admitted = admissibleIds(metas)
        val previous = snapshot
        val next = LinkedHashMap<String, AgentNode>()
        for (id in admitted.sortedWith(compareBy({ metas[it]?.spawnDepth ?: 1 }, { it }))) {
            val meta = metas[id] ?: continue
            next[id] = AgentNode(
                meta = meta,
                status = statusByToolUse[meta.toolUseId] ?: AgentStatus.RUNNING,
                entries = readTranscript(dir, id),
            )
        }
        snapshot = next
        val fresh = next.keys - previous.keys
        fresh.forEach(onAdmitted)
        return fresh.toList()
    }

    /**
     * Admission, applied until it stops growing: an agent is ours if the plugin saw its Task call, if a
     * previous plugin run recorded it, or if its parent is already ours. The fixpoint loop is what carries
     * admission down an arbitrarily deep chain in one pass — depth is not bounded by the protocol, and a
     * single pass would only ever admit one level below what it started with.
     */
    private fun admissibleIds(metas: Map<String, AgentMeta>): Set<String> {
        val admitted = metas.values
            .filter { it.agentId in preAdmitted || (it.toolUseId != null && it.toolUseId in observedToolUse) }
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

    private fun readMetas(dir: Path): Map<String, AgentMeta> = runCatching {
        Files.newDirectoryStream(dir, "*${AgentMeta.META_SUFFIX}").use { stream ->
            stream.mapNotNull { path ->
                val id = AgentMeta.agentIdOfMetaFile(path.fileName.toString()) ?: return@mapNotNull null
                val body = runCatching { Files.readString(path) }.getOrNull() ?: return@mapNotNull null
                AgentMeta.parse(id, body)?.let { id to it }
            }.toMap()
        }
    }.getOrDefault(emptyMap())

    /**
     * The agent's own transcript, parsed by the SAME reader the session restore uses. A sidecar that is not
     * there yet (the binary writes the meta first) simply yields an empty transcript, and the next scan
     * fills it — no error, no placeholder row.
     */
    private fun readTranscript(dir: Path, agentId: String): List<EntryDTO> {
        val file = dir.resolve("$agentId${AgentMeta.TRANSCRIPT_SUFFIX}")
        val lines = runCatching { Files.readAllLines(file) }.getOrNull() ?: return emptyList()
        return SessionTranscriptReader.parseEntries(lines)
    }
}
