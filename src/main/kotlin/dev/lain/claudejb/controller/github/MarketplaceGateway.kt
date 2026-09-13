package dev.lain.claudejb.controller.github

import com.intellij.util.io.HttpRequests
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.protocol.ClaudeJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.time.Instant

internal object MarketplaceGateway {

    class Update(
        val version: String,
        val channel: String,
        val listed: Boolean,
        val approved: Boolean,
        val publishedAt: String,
        val range: String,
    )

    fun updates(pluginId: Int, max: Int, fetch: (String) -> String = ::fetch): List<Update> {
        val text = try {
            fetch("$MARKETPLACE/api/plugins/$pluginId/updates?size=$max")
        } catch (e: IOException) {
            throw ToolException("Marketplace did not answer: ${e.message}", e)
        }
        val rows: JsonArray = runCatching { ClaudeJson.parseToJsonElement(text).jsonArray }
            .getOrElse { throw ToolException("Marketplace answered something that is not an update list") }
        return rows.map { row -> update(row.jsonObject) }
    }

    private fun update(row: JsonObject): Update = Update(
        version = text(row, "version"),
        channel = text(row, "channel").ifEmpty { "stable" },
        listed = text(row, "listed") == "true",
        approved = text(row, "approve") == "true",
        publishedAt = text(row, "cdate").toLongOrNull()?.let { Instant.ofEpochMilli(it).toString() }.orEmpty(),
        range = text(row, "sinceUntil"),
    )

    private fun text(row: JsonObject, key: String): String = row[key]?.jsonPrimitive?.content.orEmpty()

    private fun fetch(url: String): String = HttpRequests.request(url).readString(null)

    private const val MARKETPLACE = "https://plugins.jetbrains.com"
}
