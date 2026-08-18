package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.protocol.UsageReport
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.ui.LinkResolver
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Serializes a [ClaudeSession] into the `cc.session` dashboard payload consumed by `app-session.js`.
 *
 * Shape (see JCEF Sprint 2 contract, section cc.session):
 * <pre>
 * {
 *   context:  { categories:[{name, tokens}], used, max, pct } | null,
 *   cost:     { usd:Number|null, input, output, cacheWrite, cacheRead } | null,
 *   account:  { email, org, plan, provider } | null,
 *   agentTree:[{ agentId, label, type, status, depth, parent, chain, running }],
 *   backgroundTasks:[{ id, desc, type, agentId|null, chain }],
 *   model:    String|null,
 *   cwd:      String|null,
 *   home:     String|null,
 *   version:  String|null
 * }
 * </pre>
 *
 * Every card is null-safe: absent data emits JSON `null` (objects) or `[]` (agentTree/backgroundTasks). The
 * dashboard frontend hides any card whose data is null/empty, so a partially-populated session renders cleanly.
 *
 * This object assembles the document; **one card per builder**, each in its own file and each null-safe on its
 * own so a card can be absent without the rest noticing:
 *  - usage    ← [JcefUsageData] (the on-demand `get_usage` report, with [ClaudeSession.rateLimits] as fallback);
 *  - context  ← [JcefCostData], off [ClaudeSession.lastContextUsage] (the cached `get_context_usage` result);
 *  - cost     ← [JcefCostData], off [ClaudeSession.lastSessionCost] (raw `get_session_cost` JsonObject); the
 *               per-component token tally is decoded from an `apiUsage` block when present and the USD figure
 *               from a cost field;
 *  - account  ← [JcefAccountData], off [ClaudeSession.account] and the `auth status` probe;
 *  - git      ← [JcefGitData], off a snapshot the caller collects off the EDT (branch, HEAD, uncommitted files,
 *    recent commits) plus the applicable entries of the Git action catalogue;
 *  - agentTree← [JcefWorkloadData], off [ClaudeSession.runningAgents] (the binary's own per-agent sidecars:
 *    parentage, depth and the model-written label, so the Agents/Subagents windows draw a tree rather than a
 *    flat task list);
 *  - backgroundTasks ← [JcefWorkloadData], off [ClaudeSession.backgroundTasks] (the `background_tasks_changed`
 *    LEVEL signal — always the current set, so it cannot wedge on a missed edge). Its owning agent is resolved
 *    through [ClaudeSession.subagentTasks] when the same task_id was seen there, and left unclaimed when it
 *    was not;
 *  - model    ← [ClaudeSession.model];
 *  - cwd      ← [ClaudeSession.workingDir] (the project's base path, a synchronous getter);
 *  - home     ← [LinkResolver.userHome], and it exists ONLY so the page can write `cwd` against it as `~`.
 *    Null means "not known" and never the empty string — see the note at the `put` for why that distinction
 *    is the whole contract;
 *  - version  ← [ClaudeSession.binaryVersion], null until the async control request that asks for it has
 *    answered.
 */
object JcefSessionData {

    /**
     * One open chat and the session behind it, for the Workloads diagram.
     *
     * [chatId] is the tab strip's own handle — opaque here, and exactly what a click sends back to select
     * that chat.
     */
    data class Workload(
        val chatId: String,
        val title: String,
        val selected: Boolean,
        val session: ClaudeSession,
    )

    /**
     * The dashboard payload. [usage] is passed in rather than read off the session because it comes from an
     * on-demand `get_usage` round-trip, not from session state — the caller polls, then re-serializes.
     *
     * [workloads] is EVERY open chat, not just this one: what is running does not belong to the tab you
     * happen to be looking at, and a diagram that showed only the selected chat answered "what is running?"
     * with a fraction of the truth. **This is now the only view that answers it for more than one chat** —
     * the tab bar's second row is the open chat's work and nothing else, and the popup that used to show
     * another tab's subtree went with the `⋮`. Empty when the caller has no strip to ask (a panel outside the
     * tab strip), in which case the frontend falls back to this session's own `agentTree`/`backgroundTasks`
     * and draws it under a single root.
     *
     * [windowMinutes] and [nowMillis] are the retention window and the instant to measure it from. Both are
     * passed in rather than read here, and the clock especially: one push resolves it once and every chat in
     * that push is aged by the same instant, so two cards in one paint cannot disagree about what time it is.
     */
    fun sessionJson(
        session: ClaudeSession,
        windowMinutes: Int,
        nowMillis: Long,
        usage: UsageReport? = null,
        workloads: List<Workload> = emptyList(),
        plan: dev.lain.claudejb.session.PlanInfo? = null,
        git: JcefGitData.Snapshot? = null,
    ): String {
        val shown = JcefWorkloadData.visible(session, windowMinutes, nowMillis)
        val obj = buildJsonObject {
            put("usage", JcefUsageData.usageJson(session, usage) ?: JsonNull)
            // The plan-mode plan, when the session has one. Like every card here it is null-or-absent rather
            // than empty, so the page omits it instead of drawing a heading over nothing.
            put("plan", JcefPlanData.planJson(plan) ?: JsonNull)
            // The Git view. Same rule as every card here: null until someone has collected a snapshot off the
            // EDT, and `available:false` once it is known there is no Git — the page omits the view either way.
            put("git", JcefGitData.gitJson(git) ?: JsonNull)
            put("context", JcefCostData.contextJson(session) ?: JsonNull)
            put("cost", JcefCostData.costJson(session) ?: JsonNull)
            put("account", JcefAccountData.accountJson(session) ?: JsonNull)
            // NB no `subagents` key any more. It was the edge-derived task list, and the Agents / Subagents
            // windows replaced it with the real tree (`agentTree`) — two lists of the same thing, built from
            // different sources, is how they end up disagreeing on screen.
            put("backgroundTasks", JcefWorkloadData.backgroundTasksJson(session, shown))
            // The tree behind the Agents / Subagents windows: every agent with the chain it hangs off, so a
            // row can say "Chat |_ Agent A |_ Agent B" and link straight to that tab.
            put("agentTree", JcefWorkloadData.agentTreeJson(session, shown))
            // Every chat's tree, for the Workloads diagram. Kept ALONGSIDE the two keys above rather than
            // replacing them: they are this session's own data, which other cards read.
            put("workloads", JcefWorkloadData.workloadsJson(workloads, windowMinutes, nowMillis))
            // …and the window everything above was filtered with, plus the set of windows on offer. The
            // diagram cannot be read without it: with everything aged out, "nothing finished recently" and
            // "nothing ever ran" look identical, and the only way to tell them apart was a trip to Settings.
            // The OPTIONS travel with it so the control offers exactly the values `WorkloadWindow` applies.
            put("workloadWindow", JcefWorkloadData.windowJson(windowMinutes))
            // Always emit a friendly model label (even on a default session where session.model is null)
            // and the known working dir, so the Session card is never empty — the prior nulls made the
            // whole dashboard collapse to "No session data yet" on a fresh/idle session.
            put("model", JcefModelLabels.modelLabel(session))
            put("cwd", session.workingDir)
            // The user's home, here for ONE reason: so the page can write `cwd` against it as `~`. A path is
            // the longest value on the composer's strip and the one a narrow tool window cuts in half.
            //
            // THE PAGE CANNOT DERIVE IT. `/home/<name>` is a shape, not an identity, so a rule matching it
            // would write `~` over ANOTHER user's home — which does not read as "shortened", it reads as a
            // different directory, on the one string that answers "where is this session working". The
            // answer comes from the JVM or it does not come at all.
            //
            // NULL MEANS "NOT KNOWN", NEVER THE EMPTY STRING: an empty string is a prefix of every path, so
            // it would abbreviate all of them at once. [LinkResolver.userHome] already answers null for a
            // blank property and `put` turns that into JSON `null`; `abbreviateHome` in
            // app-composer-readout.js fails towards showing the path unchanged, which is this same contract
            // stated at the other end.
            //
            // It hands the browser no surface it did not already have — `cwd` above is a path inside that
            // home — and nothing else about the user travels with it.
            put("home", LinkResolver.userHome())
            put("version", session.binaryVersion)
        }
        return obj.toString()
    }
}
