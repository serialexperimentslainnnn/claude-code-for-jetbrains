package dev.lain.claudejb.session

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

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

    /**
     * What to call this agent on a card: `Agent` when the chat started it, `Subagent` when another agent did.
     *
     * The binary makes no such distinction — to it everything is an `agent`, and the plugin followed suit, so
     * a transcript with four levels of nesting was four rows all saying `Agent (…)`. The word is ours and it
     * is the ONE place it is decided, so the transcript, the tab bar's diagram and the Workloads diagram
     * cannot end up disagreeing about what to call the same thing.
     *
     * Parentage, not [depth]: `spawnDepth` is the binary's own counter and a restored agent can carry a value
     * that means nothing to us, while "who spawned it" is a link we read from the sidecar and admit agents by.
     */
    val kindLabel: String get() = if (parentAgentId != null) "Subagent" else "Agent"
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
    // The three seed collections are CONCURRENT, and that is not defensive dressing: they are written from
    // the EDT (the task events arrive there) and read from a pooled thread (that is where `scan` walks the
    // directory). With plain collections the worst case is not a stale label, it is a
    // ConcurrentModificationException in the middle of an admission pass. Same reasoning, same choice, as
    // TaskTracker's backing map.
    /** `tool_use_id`s of Task calls seen in this session — the seed of the admission rule. */
    private val observedToolUse: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Terminal status per `tool_use_id`, from `task_notification`. Absent means still running. */
    private val statusByToolUse = ConcurrentHashMap<String, AgentStatus>()

    /** Agent ids admitted by an outside authority (the persisted index), so a restart keeps them. */
    private val preAdmitted: MutableSet<String> = ConcurrentHashMap.newKeySet()

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

    /**
     * Re-admits agents recorded by a previous plugin run (see [PluginAgentIndex]).
     *
     * Ids are normalised on the way in: a record written before the identity became the bare id carries the
     * `agent-` prefix, and comparing that against a node key silently admits nobody — a restored chat with
     * every file on disk and not one tab. [AgentMeta.bareAgentId] carries the full account.
     */
    fun preAdmit(agentIds: Collection<String>) {
        preAdmitted += agentIds.map { AgentMeta.bareAgentId(it) }
    }

    /**
     * This chat was RESTORED: everything in its subagents directory is its own, admit it.
     *
     * Set once, by [dev.lain.claudejb.session.ClaudeSession.restore], and never cleared — a chat that came
     * back from disk keeps its history for as long as it is open, and the agents it spawns afterwards are
     * admitted by the ordinary rules anyway.
     */
    @Volatile
    var restoring: Boolean = false
        private set

    fun markRestored() {
        restoring = true
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
        // Shallowest first, so a child is always resolved AFTER the parent it inherits its ending from.
        for (id in admitted.sortedWith(compareBy({ metas[it]?.spawnDepth ?: 1 }, { it }))) {
            val meta = metas[id] ?: continue
            // Read the transcript ONCE: it is both what the tab shows and, for an agent from a previous run,
            // the only evidence of how it ended.
            val lines = readLines(dir, id)
            next[id] = AgentNode(
                meta = meta,
                status = statusOf(meta, next, lines),
                entries = SessionTranscriptReader.parseEntries(lines),
            )
        }
        snapshot = next
        val fresh = next.keys - previous.keys
        fresh.forEach(onAdmitted)
        return fresh.toList()
    }

    /**
     * What this agent is doing, in order of evidence.
     *
     * 1. **Its own `task_notification`** — the plugin saw the Task call that started it, and saw it settle.
     * 2. **Its parent's state, whatever that is.** A NESTED agent has no `toolUseId` of its own — it was
     *    spawned inside another agent's turn, so no Task call of ours ever named it — and rule 1 can never
     *    settle it. It cannot outlive the turn that spawned it, so a finished parent means a finished child;
     *    and by the same token a parent that is still working means the child belongs to live work.
     * 3. **Did we watch it start?** An agent whose Task call is in [observedToolUse] was launched by THIS
     *    process, so it is running whatever else is true.
     * 4. **How its own transcript ends** ([AgentEnding]), for an agent from a previous run. A settled status
     *    is per-process memory, so after a restart the plugin knows nothing about what it is restoring —
     *    and calling all of it "cut off" painted every agent of every past session RED, which does not just
     *    look wrong, it ASSERTS THAT THEY FAILED. Most had finished perfectly, and the binary wrote that
     *    down: a transcript ending on `stop_reason: end_turn` is an agent that said its piece and stopped.
     * 5. Otherwise RUNNING — unless the chat was RESTORED and left no transcript to judge, in which case the
     *    process it belonged to is gone and its work was cut off.
     *
     * **Rules 2 and 3 are the fix for a live report**: `restoring` is set when a chat comes back from disk
     * and is never cleared (it is what admits that chat's own subagents), so rule 5 used to swallow agents
     * launched AFTERWARDS in that same chat — and since restoring open chats is the default, every agent in
     * a freshly reopened IDE came up STOPPED, i.e. red, while it was plainly working.
     *
     * [resolved] holds the agents already built by this scan, parents first — see the sort in [scan].
     */
    private fun statusOf(meta: AgentMeta, resolved: Map<String, AgentNode>, lines: List<String>): AgentStatus {
        // Every lookup goes through `?.let`: a nested agent has NO toolUseId, and these are concurrent
        // collections, which throw on a null key rather than answering false.
        meta.toolUseId?.let { statusByToolUse[it] }?.let { return it }
        meta.parentAgentId?.let { resolved[it] }?.let { return it.status }
        if (meta.toolUseId?.let { it in observedToolUse } == true) return AgentStatus.RUNNING
        if (!restoring) return AgentStatus.RUNNING
        return AgentEnding.of(lines) ?: AgentStatus.STOPPED
    }

    /**
     * Admission, applied until it stops growing: an agent is ours if the plugin saw its Task call, if a
     * previous plugin run recorded it, or if its parent is already ours. The fixpoint loop is what carries
     * admission down an arbitrarily deep chain in one pass — depth is not bounded by the protocol, and a
     * single pass would only ever admit one level below what it started with.
     */
    private fun admissibleIds(metas: Map<String, AgentMeta>): Set<String> {
        val admitted = metas.values
            .filter {
                it.agentId in preAdmitted ||
                    (it.toolUseId != null && it.toolUseId in observedToolUse) ||
                    // RESTORED CHATS. Nobody observed a `Task` in a chat that came back from disk — the
                    // spawns happened in a previous run — so the two rules above can only ever admit what the
                    // index remembered. When the index is thin (and it is: sessions on this machine carry
                    // subagents whose level-1 parent was never recorded), the whole tree stays invisible and
                    // the chat comes back with no agents at all.
                    //
                    // The directory itself is the missing evidence: `<sessionId>/subagents/` is namespaced by
                    // session, so every sidecar in it belongs to THIS chat. The rule the filtering exists for
                    // — not adopting agents from a run started in the terminal — is about a session id we
                    // never had; it was never about hiding our own past work from us.
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
     * The agent's own transcript file, raw. It feeds two things — the rows the tab shows (through the SAME
     * reader the session restore uses) and, for an agent from a previous run, [AgentEnding] — so it is read
     * once per scan rather than once per purpose.
     *
     * A sidecar that is not there yet (the binary writes the meta first) yields nothing, and the next scan
     * fills it: no error, no placeholder row.
     */
    private fun readLines(dir: Path, agentId: String): List<String> {
        // The id is the bare one; the `agent-` prefix belongs to the file name (see AgentMeta.agentId).
        val file = dir.resolve(AgentMeta.transcriptFile(agentId))
        return runCatching { Files.readAllLines(file) }.getOrDefault(emptyList())
    }
}
