package dev.lain.claudejb.view.payload.panel

import dev.lain.claudejb.controller.session.ClaudeSession
import dev.lain.claudejb.model.protocol.models.SessionCostUsage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

internal object JcefCostData {

    fun contextJson(session: ClaudeSession): JsonObject? {
        val ctx = session.signals.lastContextUsage ?: return null
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

    fun costJson(session: ClaudeSession): JsonObject? {
        val raw = session.signals.lastSessionCost
        val usage = raw?.let { decodeApiUsage(it) }
        val counted = session.tokens
        val input = (usage?.inputTokens?.takeIf { it > 0 }) ?: counted.sessionInputTokens.toLong()
        val output = (usage?.outputTokens?.takeIf { it > 0 }) ?: counted.sessionOutputTokens.toLong()
        val cacheWrite = (usage?.cacheCreationInputTokens?.takeIf { it > 0 }) ?: counted.sessionCacheCreationTokens.toLong()
        val cacheRead = (usage?.cacheReadInputTokens?.takeIf { it > 0 }) ?: counted.sessionCacheReadTokens.toLong()
        val usd = raw?.let { usdOf(it) }
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

    private fun decodeApiUsage(raw: JsonObject): SessionCostUsage? {
        val block = (raw["apiUsage"] ?: raw["api_usage"]) as? JsonObject ?: return null
        return runCatching {
            dev.lain.claudejb.model.protocol.ClaudeJson.decodeFromJsonElement(SessionCostUsage.serializer(), block)
        }.getOrNull()
    }

    private fun usdOf(raw: JsonObject): Double? {
        for (key in listOf("total_cost_usd", "totalCostUsd", "cost_usd", "costUsd", "usd")) {
            val prim = raw[key] as? JsonPrimitive ?: continue
            prim.doubleOrNull?.let { return it }
        }
        return null
    }
}
