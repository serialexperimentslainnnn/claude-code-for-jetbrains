package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.protocol.ExtraUsage
import dev.lain.claudejb.protocol.RateLimitInfo
import dev.lain.claudejb.protocol.SessionCostUsage
import dev.lain.claudejb.protocol.UsageReport
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

    /**
     * The dashboard payload. [usage] is passed in rather than read off the session because it comes from an
     * on-demand `get_usage` round-trip, not from session state — the caller polls, then re-serializes.
     */
    fun sessionJson(session: ClaudeSession, usage: UsageReport? = null): String {
        val obj = buildJsonObject {
            put("usage", usageJson(session, usage) ?: JsonNull)
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

    /**
     * `{ plan, windows:[{ key, label, pct, resetsAt, exhausted }], extra:{…} }`, or null when nothing is known.
     *
     * Two sources, deliberately: the `get_usage` [report] is authoritative because one round-trip returns
     * EVERY window, while [ClaudeSession.rateLimits] only knows about a window once an event has moved it. The
     * events are the fallback so the panel still shows something before the first poll lands, and the nudge
     * that it is worth polling again.
     *
     * `pct` may be null — a window can be known without a percentage (the binary only sends `utilization` when
     * the API returns it). The frontend renders that as "—" and an empty bar rather than as 0%, because
     * "unknown" and "none used" are different claims and a bar cannot show both.
     */
    private fun usageJson(session: ClaudeSession, report: UsageReport?): JsonObject? {
        // EXPERIMENT (Lain's comma test): carry the decimals — do NOT round to Int — so we can see whether the
        // frontend renders a fractional percentage with a comma (locale formatting in play) or a dot.
        val fromReport = report?.windows?.map { (key, w) ->
            Window(key, w.utilization, w.resetsAt, exhausted = false)
        }.orEmpty()
        val fromEvents = session.rateLimits
            .filterKeys { key -> fromReport.none { it.key == key } }
            .map { (key, info) ->
                Window(key, info.utilization?.let { it * 100 }, info.resetsAt?.let(::isoOf), info.isExhausted)
            }
        val windows = fromReport + fromEvents
        if (windows.isEmpty() && report?.extra == null) return null
        return buildJsonObject {
            put("plan", report?.subscriptionType ?: session.account?.subscriptionType?.ifBlank { null })
            put(
                "windows",
                buildJsonArray {
                    windows.forEach { w ->
                        addJsonObject {
                            put("key", w.key)
                            put("label", RateLimitInfo.windowTitleFor(w.key))
                            put("pct", w.pct)
                            put("resetsAt", w.resetsAt)
                            put("exhausted", w.exhausted)
                        }
                    }
                },
            )
            put("extra", report?.extra?.let { extraUsageJson(it) } ?: JsonNull)
        }
    }

    private data class Window(val key: String, val pct: Double?, val resetsAt: String?, val exhausted: Boolean)

    /** The pay-as-you-go balance. Credits are minor units (`decimal_places`), not whole currency. */
    private fun extraUsageJson(extra: ExtraUsage): JsonObject = buildJsonObject {
        put("enabled", extra.isEnabled)
        put("spent", extra.usedCredits?.let { it / TEN.pow(extra.decimalPlaces) })
        put("limit", extra.monthlyLimit)
        put("currency", extra.currency)
        put("pct", extra.utilization) // EXPERIMENT: raw, un-rounded, like the windows — so the decimal shows
        put("limitReached", extra.spendLimitReached)
    }

    /** Epoch seconds → ISO-8601, so event-sourced windows match the shape `get_usage` already returns. */
    private fun isoOf(epochSeconds: Long): String =
        java.time.Instant.ofEpochSecond(epochSeconds).toString()

    private const val TEN = 10.0

    private fun Double.pow(exp: Int): Double = Math.pow(this, exp.toDouble())

    /** `{ categories:[{name, tokens}], used, max, pct }` or null when no context usage has been polled yet. */
    private fun contextJson(session: ClaudeSession): JsonObject? {
        val ctx = session.lastContextUsage ?: return null
        return buildJsonObject {
            put(
                "categories",
                buildJsonArray {
                    ctx.categories.forEach { cat ->
                        addJsonObject {
                            put("name", cat.name)
                            put("tokens", cat.tokens)
                        }
                    }
                },
            )
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
        // Nothing measured yet → omit the card entirely rather than render a row of zeros.
        val noTokens = listOf(input, output, cacheWrite, cacheRead).all { it == 0L }
        if (noTokens && usd == null) return null
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

    /**
     * `{ email, org, plan, provider, loggedIn }` — session-reported account fields, enriched by the
     * `auth status` probe ([ClaudeSession.authCliStatus]), which also knows about a session that has no
     * account because it is NOT signed in. `loggedIn` drives the dashboard's Sign in / Log out button:
     * absent (null) when unknown, so the button doesn't claim a state nobody verified.
     */
    private fun accountJson(session: ClaudeSession): JsonObject? {
        val acct = session.account
        val probe = session.authCliStatus
        // The stored `auth status` reply — the binary's own words, filed in the safe by the last probe. The
        // probe is a process spawn and cannot run on every push, so without this the card had nothing to show
        // between probes and the Email / Organization rows sat empty.
        val stored = dev.lain.claudejb.process.AuthCli.stored()
        val empty = acct.email.isBlank() && acct.organization.isBlank() &&
            acct.subscriptionType.isBlank() && acct.apiProvider.isBlank() && probe == null && stored == null
        if (empty) return null
        return buildJsonObject {
            put("email", firstPresent(acct.email, probe?.email, stored?.email))
            put("org", firstPresent(acct.organization, probe?.orgName, stored?.orgName))
            // Last resort, the vaulted blob: the plan is also carried inside the credential we hold, so the
            // row survives even a session that never managed to probe.
            put(
                "plan",
                firstPresent(
                    acct.subscriptionType,
                    probe?.subscriptionType,
                    stored?.subscriptionType,
                    dev.lain.claudejb.process.CredentialsVault.subscriptionType(),
                ),
            )
            // `apiProvider` ("firstParty") before `authMethod` ("claude.ai"): both describe the route, and the
            // former is the one the session's own account event uses, so the row can't change vocabulary
            // depending on which source answered.
            put(
                "provider",
                firstPresent(
                    acct.apiProvider,
                    probe?.apiProvider,
                    probe?.authMethod,
                    stored?.apiProvider,
                    stored?.authMethod,
                ),
            )
            // The stored reply counts as verified: it IS a past `auth status`, and Log out clears the safe
            // (AUTH_STATUS included), so it cannot outlive the identity it describes.
            put("loggedIn", probe?.loggedIn ?: stored?.loggedIn)
        }
    }

    /**
     * The first candidate that carries something, or null. Blank counts as absent: the session's own account
     * object reports its unknown fields as `""`, and an empty string is a value the frontend would happily
     * render as a present-but-empty row.
     */
    internal fun firstPresent(vararg candidates: String?): String? =
        candidates.firstOrNull { !it.isNullOrBlank() }

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
