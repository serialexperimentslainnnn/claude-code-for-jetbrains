package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.session.AgentStatus
import dev.lain.claudejb.vuln.ScanSilence
import dev.lain.claudejb.vuln.VulnComponent
import dev.lain.claudejb.vuln.VulnDisclosure
import dev.lain.claudejb.vuln.VulnFinding
import dev.lain.claudejb.vuln.VulnReport
import dev.lain.claudejb.vuln.VulnSnapshot
import dev.lain.claudejb.vuln.VulnViewState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object JcefVulnData {

    const val MAX_FINDINGS = 400

    const val MAX_INVENTORY_ROWS = 4000

    fun vulnJson(snapshot: VulnSnapshot?): JsonObject? {
        if (snapshot == null) return null
        return buildJsonObject {
            put("available", true)
            put("state", snapshot.state.wire)
            put("status", statusWord(snapshot))
            put("consent", snapshot.consent.wire)
            put("endpoint", snapshot.endpoint)
            put("operator", VulnDisclosure.OPERATOR)
            put("disclosure", disclosureJson())
            put("inventory", inventorySummaryJson(snapshot))
            put("progress", progressJson(snapshot))
            put("reason", snapshot.silence?.wire)
            put("note", snapshot.silence?.note)
            put("report", reportJson(snapshot.report))
        }
    }

    fun inventoryJson(components: List<VulnComponent>, endpoint: String): JsonObject = buildJsonObject {
        put("endpoint", endpoint)
        put("operator", VulnDisclosure.OPERATOR)
        put("total", components.size)
        put("truncated", components.size > MAX_INVENTORY_ROWS)
        put(
            "components",
            buildJsonArray {
                components.take(MAX_INVENTORY_ROWS).forEach { component ->
                    addJsonObject {
                        put("ecosystem", component.ecosystem)
                        put("name", component.name)
                        put("version", component.version)
                        put("origin", component.origin.wire)
                        put("originLabel", component.origin.label)
                        put("manifest", component.manifest)
                    }
                }
            },
        )
    }

    private fun statusWord(snapshot: VulnSnapshot): String = JcefStatus.of(
        when {
            snapshot.state == VulnViewState.SCANNING -> AgentStatus.RUNNING
            snapshot.state == VulnViewState.RESULTS -> AgentStatus.COMPLETED
            snapshot.state == VulnViewState.FAILED && snapshot.silence != ScanSilence.CANCELLED -> AgentStatus.FAILED
            else -> AgentStatus.STOPPED
        },
    )

    private fun disclosureJson(): JsonObject = buildJsonObject {
        put("sent", buildJsonArray { VulnDisclosure.SENT.forEach { add(it) } })
        put("caveats", buildJsonArray { VulnDisclosure.CAVEATS.forEach { add(it) } })
    }

    private fun inventorySummaryJson(snapshot: VulnSnapshot): JsonObject = buildJsonObject {
        put("components", snapshot.componentCount)
        put("manifests", buildJsonArray { snapshot.manifests.forEach { add(it) } })
        put("ecosystems", buildJsonArray { snapshot.ecosystems.forEach { add(it) } })
    }

    private fun progressJson(snapshot: VulnSnapshot): JsonObject = buildJsonObject {
        put("done", snapshot.done)
        put("total", snapshot.total)
    }

    private fun reportJson(report: VulnReport?): JsonElement {
        if (report == null) return JsonNull
        val ordered = report.ordered()
        return buildJsonObject {
            put("asOfMillis", report.asOfMillis)
            put("endpoint", report.endpoint)
            put("queried", report.queried)
            put("total", ordered.size)
            put("shown", minOf(ordered.size, MAX_FINDINGS))
            put("counts", countsJson(report))
            put("findings", findingsJson(ordered.take(MAX_FINDINGS)))
        }
    }

    private fun countsJson(report: VulnReport) = buildJsonArray {
        report.tierCounts().forEach { (tier, count) ->
            addJsonObject {
                put("tier", tier.wire)
                put("label", tier.label)
                put("count", count)
            }
        }
    }

    private fun findingsJson(findings: List<VulnFinding>) = buildJsonArray {
        findings.forEach { finding ->
            addJsonObject {
                put("id", finding.id)
                put("tier", finding.tier.wire)
                put("tierLabel", finding.tier.label)
                put("malicious", finding.malicious)
                put("name", finding.component.name)
                put("version", finding.component.version)
                put("ecosystem", finding.component.ecosystem)
                put("origin", finding.component.origin.wire)
                put("originLabel", finding.component.origin.label)
                put("manifest", finding.component.manifest)
                put("summary", finding.summary)
                put("details", finding.details)
                put("cvss", finding.severity?.cvss?.vector)
                put("cvssType", finding.severity?.cvss?.type)
                put("published", finding.publishedIso)
                put("fixed", buildJsonArray { finding.fixedVersions.forEach { add(it) } })
                put("aliases", buildJsonArray { finding.aliases.forEach { add(it) } })
                put("references", buildJsonArray { finding.references.filter(::isWebUrl).forEach { add(it) } })
            }
        }
    }

    private fun isWebUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.startsWith("https://") || lower.startsWith("http://")
    }
}
