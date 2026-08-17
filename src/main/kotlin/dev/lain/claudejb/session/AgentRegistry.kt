package dev.lain.claudejb.session

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Lifecycle of one agent, as far as the plugin can honestly tell. */
enum class AgentStatus { RUNNING, COMPLETED, FAILED, STOPPED }

/**
 * One agent as the UI needs it: what the binary says about it ([meta]), how it ended ([status]), its
 * reconstructed transcript ([entries]) and when it stopped running ([completedAtMillis]).
 */
data class AgentNode(
    val meta: AgentMeta,
    val status: AgentStatus = AgentStatus.RUNNING,
    val entries: List<EntryDTO> = emptyList(),
    /**
     * The instant this agent stopped being [AgentStatus.RUNNING], in epoch milliseconds; `null` while it is
     * still running.
     *
     * Every settled agent carries one. When the ending was watched live, it is the instant it was watched;
     * when the agent arrives already finished — its ending read off a file left by a previous run — it is
     * [WorkloadWindow.RUN_STARTED_AT], the instant this run first saw it. That is what keeps one rule over
     * every agent: what came back from disk is shown on reopening and ages out like everything else, instead
     * of forming a second class the retention window can never reach.
     */
    val completedAtMillis: Long? = null,
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
 * **A settled agent is not a closed case.** An agent can be resumed, and no signal says so — the only trace is
 * that its transcript grows past the ending already accounted for. So every pass compares the number of records
 * the one parser finds in that file against the count it was last accounted at, and growth drops the settled
 * state so the ordinary rules answer again ([reopenIfGrown]). Without it a resumed agent stays green for as long
 * as the chat is open, since a settled status is written once and read first.
 *
 * Threading: [scan] does blocking IO and must run off the EDT; [nodes] is a snapshot, safe to read anywhere.
 *
 * [now] is the registry's ONLY clock, and it is a parameter so a test can state the expected instant as a
 * literal instead of recomputing it. Nothing else here reads the wall clock: an agent's stop instant is
 * sealed where the live signal arrives ([observeSettled]), or at admission for one that arrives already
 * finished — never where it is rendered.
 *
 * [runStartedAtMillis] is that admission stamp, and it is ONE value for the whole run ([WorkloadWindow]):
 * stamping each restored agent with its own reading would make agents that came back together expire at
 * different moments, for no reason a user could name.
 */
class AgentRegistry(
    private val subagentsDir: () -> Path?,
    private val onAdmitted: (agentId: String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val runStartedAtMillis: Long = WorkloadWindow.RUN_STARTED_AT,
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

    /**
     * When each `tool_use_id` stopped running, sealed at the first live signal that settled it.
     *
     * It lives alongside [statusByToolUse] and is keyed the same way, which is what makes repeated [scan]
     * calls rebuild the SAME stamp with no memory of the previous snapshot: the instant belongs to the Task
     * call, not to the pass that reads it. Both are dropped together, and only where there is evidence the
     * agent is working again — see [reopenIfGrown].
     */
    private val completedAtByToolUse = ConcurrentHashMap<String, Long>()

    /**
     * Per agent id, the number of parseable records in its transcript when its ending was last accounted for —
     * the baseline [reopenIfGrown] compares against.
     *
     * Concurrent for the reason the seed collections above are: it is written where the pass runs, on a pooled
     * thread, and lives alongside maps the EDT writes.
     */
    private val accountedRecordsByAgent = ConcurrentHashMap<String, Int>()

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

    /**
     * A `task_notification` said something about a Task call — the agent's tab keeps its transcript, marked
     * with this.
     *
     * **Only an ENDING ends it**, which is the rule [BackgroundTaskRegistry.settle] already states and this one
     * did not follow. `task_notification` is emitted for work in progress too — the binary sends `started`,
     * `running`, `in_progress` down the same channel, and every one of them arrives here as
     * [AgentStatus.RUNNING] — so sealing an instant unconditionally stamped an agent as having stopped seconds
     * after it began. Nothing could correct that afterwards, because the seal is written once: the agent then
     * left the retention window at the very moment it really finished, which is the opposite of what the
     * window is for. A live status therefore DROPS the instant instead of writing one — the same thing
     * [BackgroundTaskRegistry.observeLevel] does when a settled task is listed as live again.
     *
     * An ending's instant is sealed ONCE: `task_notification` repeats for the same call, and a second write
     * would rejuvenate an agent that stopped minutes ago. Only new evidence unseals it — a transcript that grew
     * ([reopenIfGrown]) — so the next ending is stamped when it is watched instead of carrying the previous
     * one. The put is atomic because the write happens on the EDT while [scan] reads on a pooled thread.
     *
     * **The ending also drops the growth baseline, and without that it did not survive the next pass.** The
     * records the agent wrote on its way to this ending — at minimum the finished turn itself — land in
     * `agent-<id>.jsonl` AFTER the scan that last counted it, so the very next scan reads them as growth and
     * [reopenIfGrown] deletes the settle written a moment earlier. The agent then read RUNNING with no instant,
     * forever: later passes see no further growth, so nothing ever settles it again; the revival poll cannot
     * rescue it either, since that poll only runs while something is settled. Forgetting the baseline instead
     * of comparing against it is the whole fix — the next pass records the current count and reopens nobody
     * ("an absent baseline reopens nothing"), and growth AFTER this ending still reopens the agent, which is
     * exactly what the rule means.
     */
    fun observeSettled(toolUseId: String?, status: AgentStatus) {
        if (toolUseId.isNullOrBlank()) return
        statusByToolUse[toolUseId] = status
        if (status == AgentStatus.RUNNING) {
            completedAtByToolUse.remove(toolUseId)
            return
        }
        completedAtByToolUse.putIfAbsent(toolUseId, now())
        // Keyed by agent id, while everything above is keyed by the Task call — the snapshot is what relates
        // the two, and it holds every agent a scan has ever counted, which is every agent with a baseline.
        snapshot.values.forEach { node ->
            if (node.meta.toolUseId == toolUseId) accountedRecordsByAgent.remove(node.agentId)
        }
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
            // Read once, parsed once, and each result serves every purpose that needs it: the raw lines are the
            // evidence of how the agent ended, while the parsed records are both the rows the tab shows and the
            // count growth is measured in — so the ONE parser is also the one definition of a record.
            val lines = readLines(dir, id)
            val entries = SessionTranscriptReader.parseEntries(lines)
            reopenIfGrown(meta, entries.size)
            val settled = settledStateOf(meta, next, lines)
            next[id] = AgentNode(
                meta = meta,
                status = settled.status,
                entries = entries,
                completedAtMillis = settled.completedAtMillis,
            )
        }
        snapshot = next
        val fresh = next.keys - previous.keys
        fresh.forEach(onAdmitted)
        return fresh.toList()
    }

    /** The two things one rule decides together: what the agent is doing, and since when it stopped. */
    private data class Settled(val status: AgentStatus, val completedAtMillis: Long?)

    /**
     * Drops the settled state of an agent whose transcript has GROWN since its ending was last accounted for,
     * so a settled state is a verdict about evidence rather than a permanent one.
     *
     * An agent can be resumed: the binary writes more records to the very same `agent-<id>.jsonl`, and nothing
     * in the stream announces it — a `task_notification` settles a Task call once and never unsettles it. So the
     * transcript is the announcement, and the evidence is **the number of PARSEABLE RECORDS in the agent's own
     * file**, as counted by [SessionTranscriptReader.parseEntries], compared against the count recorded the last
     * time this agent's ending was accounted for.
     *
     * **Records and not lines**, because a line is not a record. A JSONL truncated mid-write leaves a partial
     * last line, and counting lines makes every half-written append a reopening — the agent flickers between
     * finished and running while nothing about it changed. It also has to be the SAME notion of a record that
     * [AgentEnding] judges the ending by, or the two disagree: one trailing unparseable line would reopen the
     * agent here while the transcript still ends on a finished turn. [SessionTranscriptReader.parseEntries] is
     * this repository's ONE JSONL parser, so taking its output as the count is what keeps a single criterion
     * instead of a second one written by hand.
     *
     * **It costs nothing.** [scan] already parses every agent's file once per pass to build its rows; the count
     * is the size of that same result.
     *
     * **Monotonic, which is what idempotence rests on.** The file is append-only, and the parser only ever
     * appends an entry per record it recognises — the one pass that shrinks its output ([SessionTranscriptReader]
     * caps a tail) is not engaged here, since [scan] asks for the whole transcript. So the count rises or stays,
     * and "strictly greater" means records were added.
     *
     * **An absent baseline reopens nothing.** The first pass that sees an agent records its count and stops
     * there; without that, every agent restored from a previous run would come back RUNNING on the first scan,
     * since its whole transcript would read as growth.
     *
     * **That is also what a fresh ending relies on**, and the two rules only make sense read together.
     * [observeSettled] drops the baseline when it seals an ending, because the records that carried the agent
     * to it were written after the pass that last counted them — so without that drop this rule read an ending
     * as a resumption and deleted the settle one line after it was written. See [observeSettled] for what that
     * cost.
     *
     * With no baseline crossed nothing is removed, so an agent that finished and never writes again keeps both
     * its status and its stop instant across any number of passes.
     *
     * A nested agent has no `toolUseId`, so there is nothing of its own to drop — it inherits its parent's
     * state (rule 2 of [observedStateOf]), and a revived parent is what brings it back. Its own growth is not
     * independent evidence, because it cannot run outside the turn that spawned it.
     */
    private fun reopenIfGrown(meta: AgentMeta, count: Int) {
        val accounted = accountedRecordsByAgent.put(meta.agentId, count) ?: return
        if (count <= accounted) return
        // Through `?.let`, like every other lookup here: a nested agent has no `toolUseId`, and a concurrent
        // map throws on a null key rather than answering that it holds nothing for it.
        meta.toolUseId?.let { id ->
            statusByToolUse.remove(id)
            completedAtByToolUse.remove(id)
        }
    }

    /**
     * How a transcript's ending reads as a status.
     *
     * [AgentEnding.Ending.RESUMED] is RUNNING and stays distinct from [AgentEnding.Ending.UNFINISHED]: a
     * transcript that grew past a turn it had already closed is an agent working again, while one that never
     * closed a turn belongs to a process that is gone. Collapsing the two paints one of them wrongly, whichever
     * word is chosen. Nothing to judge is the same as never finishing — a restored chat whose agent left no
     * parseable record cannot be vouched for as live.
     */
    private fun statusOf(ending: AgentEnding.Ending?): AgentStatus = when (ending) {
        AgentEnding.Ending.COMPLETED -> AgentStatus.COMPLETED
        AgentEnding.Ending.RESUMED -> AgentStatus.RUNNING
        AgentEnding.Ending.UNFINISHED, null -> AgentStatus.STOPPED
    }

    /**
     * The agent's state, with a stop instant on everything that has stopped.
     *
     * [observedStateOf] answers from evidence and leaves the instant empty when nobody here watched the
     * ending. This is where that gap is closed, at ADMISSION: an agent that is already finished and carries
     * no instant is stamped with [runStartedAtMillis], the one instant of this run. So an agent restored from
     * a previous run obeys the same retention window as everything else — visible when the IDE reopens, gone
     * on its own once the configured time has passed since then.
     *
     * An instant already written is never overwritten: only an empty one is filled, and a running agent is
     * left alone until it settles.
     */
    private fun settledStateOf(meta: AgentMeta, resolved: Map<String, AgentNode>, lines: List<String>): Settled {
        val observed = observedStateOf(meta, resolved, lines)
        if (observed.status == AgentStatus.RUNNING || observed.completedAtMillis != null) return observed
        return observed.copy(completedAtMillis = runStartedAtMillis)
    }

    /**
     * What this agent is doing, in order of evidence — and, with the same rule, when it stopped.
     *
     * 1. **Its own `task_notification`** — the plugin saw the Task call that started it, and saw it settle.
     *    The stamp is the one sealed for that call in [observeSettled], so it is the instant the ending was
     *    observed and not the instant this scan happens to run. It answers first and it is not permanent:
     *    [reopenIfGrown] drops it when the agent's transcript grows, and the rules below then answer a resumed
     *    agent as the live work it is.
     * 2. **Its parent's state, whatever that is.** A NESTED agent has no `toolUseId` of its own — it was
     *    spawned inside another agent's turn, so no Task call of ours ever named it — and rule 1 can never
     *    settle it. It cannot outlive the turn that spawned it, so a finished parent means a finished child;
     *    and by the same token a parent that is still working means the child belongs to live work. It
     *    therefore inherits the parent's instant too — the child stopped when the turn did — and inherits
     *    nothing while that parent is still running.
     * 3. **Did we watch it start?** An agent whose Task call is in [observedToolUse] was launched by THIS
     *    process, so it is running whatever else is true — and a running agent has no stop instant.
     * 4. **How its own transcript ends** ([AgentEnding]), for an agent from a previous run. A settled status
     *    is per-process memory, so after a restart the plugin knows nothing about what it is restoring —
     *    and calling all of it "cut off" painted every agent of every past session RED, which does not just
     *    look wrong, it ASSERTS THAT THEY FAILED. Most had finished perfectly, and the binary wrote that
     *    down: a transcript ending on `stop_reason: end_turn` is an agent that said its piece and stopped, and
     *    one that grew past such an ending is an agent that was resumed ([statusOf]).
     *    No instant comes with it: that status is read off a file, not watched, so nothing here can vouch
     *    for when it happened — [settledStateOf] stamps it at admission instead.
     * 5. Otherwise RUNNING — unless the chat was RESTORED and left no transcript to judge, in which case the
     *    process it belonged to is gone and its work was cut off, again with nothing to date it by.
     *
     * **Rules 2 and 3 are the fix for a live report**: `restoring` is set when a chat comes back from disk
     * and is never cleared (it is what admits that chat's own subagents), so rule 5 used to swallow agents
     * launched AFTERWARDS in that same chat — and since restoring open chats is the default, every agent in
     * a freshly reopened IDE came up STOPPED, i.e. red, while it was plainly working.
     *
     * [resolved] holds the agents already built by this scan, parents first — see the sort in [scan].
     */
    private fun observedStateOf(meta: AgentMeta, resolved: Map<String, AgentNode>, lines: List<String>): Settled {
        // Every lookup goes through `?.let`: a nested agent has NO toolUseId, and these are concurrent
        // collections, which throw on a null key rather than answering false.
        meta.toolUseId?.let { id ->
            statusByToolUse[id]?.let { return Settled(it, completedAtByToolUse[id]) }
        }
        meta.parentAgentId?.let { resolved[it] }?.let { return Settled(it.status, it.completedAtMillis) }
        if (meta.toolUseId?.let { it in observedToolUse } == true) return Settled(AgentStatus.RUNNING, null)
        if (!restoring) return Settled(AgentStatus.RUNNING, null)
        return Settled(statusOf(AgentEnding.of(lines)), null)
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
