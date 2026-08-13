package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.protocol.SessionCostUsage
import dev.lain.claudejb.session.ClaudeSession
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * The dashboard's two measured cards — what the context window holds, and what the session has spent. One
 * part of [JcefSessionData]'s payload; see there for the whole shape and for where each source comes from.
 */
internal object JcefCostData {

    /** `{ categories:[{name, tokens}], used, max, pct }` or null when no context usage has been polled yet. */
    fun contextJson(session: ClaudeSession): JsonObject? {
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
    fun costJson(session: ClaudeSession): JsonObject? {
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
}
