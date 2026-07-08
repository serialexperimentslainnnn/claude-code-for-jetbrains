package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.protocol.SessionCostUsage
import dev.lain.claudejb.session.ClaudeSession
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
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
 *   subagents:[{ id, desc, type, status, tokens, tools }],
 *   backgroundTasks:[{ id, desc, type }],
 *   model:    String|null,
 *   cwd:      String|null,
 *   version:  String|null
 * }
 * </pre>
 *
 * Every card is null-safe: absent data emits JSON `null` (objects) or `[]` (subagents/backgroundTasks). The
 * dashboard frontend hides any card whose data is null/empty, so a partially-populated session renders cleanly.
 *
 * Sources:
 *  - context  ← [ClaudeSession.lastContextUsage] (the cached `get_context_usage` result);
 *  - cost     ← [ClaudeSession.lastSessionCost] (raw `get_session_cost` JsonObject); the per-component token
 *               tally is decoded from an `apiUsage` block when present and the USD figure from a cost field;
 *  - account  ← [ClaudeSession.account];
 *  - subagents← [ClaudeSession.subagentTasks] (edge-derived: task_started/progress/updated/notification);
 *  - backgroundTasks ← [ClaudeSession.backgroundTasks] (the `background_tasks_changed` LEVEL signal — always the
 *    current set, so it cannot wedge on a missed edge; deliberately NOT correlated with `subagents`);
 *  - model    ← [ClaudeSession.model];
 *  - cwd/version: [ClaudeSession] exposes no synchronous getter for either (cwd arrives only ephemerally on
 *    the `system/init` event and the binary version only via an async control request), so both are emitted
 *    as `null` per the contract.
 */
object JcefSessionData {

    fun sessionJson(session: ClaudeSession): String {
        val obj = buildJsonObject {
            put("context", contextJson(session) ?: JsonNull)
            put("cost", costJson(session) ?: JsonNull)
            put("account", accountJson(session) ?: JsonNull)
            put("subagents", subagentsJson(session))
            put("backgroundTasks", backgroundTasksJson(session))
            // Always emit a friendly model label (even on a default session where session.model is null)
            // and the known working dir, so the Session card is never empty — the prior nulls made the
            // whole dashboard collapse to "No session data yet" on a fresh/idle session.
            put("model", JcefState.modelLabel(session))
            put("cwd", session.workingDir)
            put("version", session.binaryVersion)
        }
        return obj.toString()
    }

    /** `{ categories:[{name, tokens}], used, max, pct }` or null when no context usage has been polled yet. */
    private fun contextJson(session: ClaudeSession): JsonObject? {
        val ctx = session.lastContextUsage ?: return null
        return buildJsonObject {
            put("categories", buildJsonArray {
                ctx.categories.forEach { cat ->
                    addJsonObject {
                        put("name", cat.name)
                        put("tokens", cat.tokens)
                    }
                }
            })
            put("used", ctx.totalTokens)
            put("max", ctx.maxTokens)
            put("pct", ctx.percentage)
        }
    }

    /**
     * `{ usd, input, output, cacheWrite, cacheRead }` or null when no session cost has been polled yet.
     * Token components are decoded from an `apiUsage` (or `api_usage`) block in the raw cost payload when
     * present, defaulting to 0 otherwise. `usd` is read from the first present cost field (or null).
     */
    private fun costJson(session: ClaudeSession): JsonObject? {
        val raw = session.lastSessionCost
        val usage = raw?.let { decodeApiUsage(it) }
        // Prefer the binary's authoritative apiUsage; fall back to the locally-folded counters so the
        // card shows real numbers even when get_session_cost hasn't reported apiUsage yet (was all 0s).
        val input = (usage?.inputTokens?.takeIf { it > 0 }) ?: session.sessionInputTokens.toLong()
        val output = (usage?.outputTokens?.takeIf { it > 0 }) ?: session.sessionOutputTokens.toLong()
        val cacheWrite = (usage?.cacheCreationInputTokens?.takeIf { it > 0 }) ?: session.sessionCacheCreationTokens.toLong()
        val cacheRead = (usage?.cacheReadInputTokens?.takeIf { it > 0 }) ?: session.sessionCacheReadTokens.toLong()
        val usd = raw?.let { usdOf(it) }
        if (input == 0L && output == 0L && cacheWrite == 0L && cacheRead == 0L && usd == null) return null
        return buildJsonObject {
            put("usd", usd)
            put("input", input)
            put("output", output)
            put("cacheWrite", cacheWrite)
            put("cacheRead", cacheRead)
        }
    }

    /** Decode the `apiUsage`/`api_usage` block into [SessionCostUsage], or null if absent/undecodable. */
    private fun decodeApiUsage(raw: JsonObject): SessionCostUsage? {
        val block = (raw["apiUsage"] ?: raw["api_usage"]) as? JsonObject ?: return null
        return runCatching {
            dev.lain.claudejb.protocol.ClaudeJson.decodeFromJsonElement(SessionCostUsage.serializer(), block)
        }.getOrNull()
    }

    /** The cumulative USD cost, read from the first present numeric cost field, or null when not derivable. */
    private fun usdOf(raw: JsonObject): Double? {
        for (key in listOf("total_cost_usd", "totalCostUsd", "cost_usd", "costUsd", "usd")) {
            val prim = raw[key] as? JsonPrimitive ?: continue
            prim.doubleOrNull?.let { return it }
        }
        return null
    }

    /** `{ email, org, plan, provider }` or null when the account is empty (no fields reported). */
    private fun accountJson(session: ClaudeSession): JsonObject? {
        val acct = session.account
        val empty = acct.email.isBlank() && acct.organization.isBlank() &&
            acct.subscriptionType.isBlank() && acct.apiProvider.isBlank()
        if (empty) return null
        return buildJsonObject {
            put("email", acct.email.ifBlank { null })
            put("org", acct.organization.ifBlank { null })
            put("plan", acct.subscriptionType.ifBlank { null })
            put("provider", acct.apiProvider.ifBlank { null })
        }
    }

    /** One row per subagent task: `{ id, desc, type, status, tokens, tools }`; empty array when none. */
    private fun subagentsJson(session: ClaudeSession) = buildJsonArray {
        session.subagentTasks.values.forEach { task ->
            addJsonObject {
                put("id", task.taskId)
                put("desc", task.description)
                put("type", task.subagentType)
                put("status", task.status)
                put("tokens", task.usage.totalTokens)
                put("tools", task.usage.toolUses)
            }
        }
    }

    /**
     * One row per live background task: `{ id, desc, type }`; empty array when none. Sourced from the
     * `background_tasks_changed` LEVEL signal, so it always reflects the *current* set — it can't wedge on a
     * missed edge the way the subagent list can, and it is deliberately not correlated with it.
     */
    private fun backgroundTasksJson(session: ClaudeSession) = buildJsonArray {
        session.backgroundTasks.forEach { task ->
            addJsonObject {
                put("id", task.taskId)
                put("desc", task.description)
                put("type", task.taskType)
            }
        }
    }
}
