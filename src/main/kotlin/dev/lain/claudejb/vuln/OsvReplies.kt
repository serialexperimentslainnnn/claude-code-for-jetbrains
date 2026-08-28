package dev.lain.claudejb.vuln

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object OsvReplies {

    private const val MALICIOUS_PREFIX = "MAL-"

    private val JSON = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    fun affectedFlags(body: String): List<Boolean>? =
        decode(body, BatchReply.serializer())?.results?.map { it.vulns.isNotEmpty() }

    fun findings(body: String, component: VulnComponent): List<VulnFinding>? =
        decode(body, QueryReply.serializer())?.vulns?.map { it.toFinding(component) }

    private fun <T> decode(body: String, serializer: KSerializer<T>): T? =
        runCatching { JSON.decodeFromString(serializer, body) }.getOrNull()

    private fun OsvVuln.toFinding(component: VulnComponent) = VulnFinding(
        id = id,
        component = component,
        malicious = id.startsWith(MALICIOUS_PREFIX, ignoreCase = true),
        severity = severityOf(),
        summary = summary?.ifBlank { null },
        details = details?.ifBlank { null },
        fixedVersions = affected.flatMap { it.ranges }.flatMap { it.events }.mapNotNull { it.fixed }.distinct(),
        references = references.mapNotNull { it.url?.ifBlank { null } }.distinct(),
        aliases = aliases,
        publishedIso = published?.ifBlank { null },
    )

    private fun OsvVuln.severityOf(): VulnSeverity? {
        val vector = severity.firstOrNull { !it.score.isNullOrBlank() }
            ?.let { CvssVector(it.type?.ifBlank { null } ?: "CVSS", it.score.orEmpty()) }
        val tier = tierOf(databaseSpecific?.severity ?: affected.firstNotNullOfOrNull { it.databaseSpecific?.severity })
        if (vector == null && tier == VulnTier.UNRATED) return null
        return VulnSeverity(tier, vector)
    }

    private fun tierOf(word: String?): VulnTier = when (word?.uppercase()) {
        "CRITICAL" -> VulnTier.CRITICAL
        "HIGH" -> VulnTier.HIGH
        "MODERATE", "MEDIUM" -> VulnTier.MODERATE
        "LOW" -> VulnTier.LOW
        else -> VulnTier.UNRATED
    }
}

@Serializable
private data class BatchReply(val results: List<BatchResult> = emptyList())

@Serializable
private data class BatchResult(val vulns: List<BatchVuln> = emptyList())

@Serializable
private data class BatchVuln(val id: String = "")

@Serializable
private data class QueryReply(val vulns: List<OsvVuln> = emptyList())

@Serializable
private data class OsvVuln(
    val id: String = "",
    val summary: String? = null,
    val details: String? = null,
    val aliases: List<String> = emptyList(),
    val published: String? = null,
    val severity: List<OsvSeverity> = emptyList(),
    val affected: List<OsvAffected> = emptyList(),
    val references: List<OsvReference> = emptyList(),
    @SerialName("database_specific") val databaseSpecific: OsvDatabaseSpecific? = null,
)

@Serializable
private data class OsvSeverity(val type: String? = null, val score: String? = null)

@Serializable
private data class OsvAffected(
    val ranges: List<OsvRange> = emptyList(),
    @SerialName("database_specific") val databaseSpecific: OsvDatabaseSpecific? = null,
)

@Serializable
private data class OsvRange(val events: List<OsvEvent> = emptyList())

@Serializable
private data class OsvEvent(val introduced: String? = null, val fixed: String? = null)

@Serializable
private data class OsvReference(val type: String? = null, val url: String? = null)

@Serializable
private data class OsvDatabaseSpecific(val severity: String? = null)
