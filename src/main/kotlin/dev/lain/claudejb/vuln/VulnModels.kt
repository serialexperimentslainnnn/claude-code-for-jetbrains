package dev.lain.claudejb.vuln

enum class ComponentOrigin(val wire: String, val label: String) {
    DIRECT("direct", "direct dependency"),
    TRANSITIVE("transitive", "transitive dependency"),
    UNKNOWN("unknown", "origin not recorded by this manifest"),
}

data class VulnComponent(
    val ecosystem: String,
    val name: String,
    val version: String,
    val origin: ComponentOrigin,
    val manifest: String,
)

enum class VulnTier(val wire: String, val label: String) {
    MALICIOUS("malicious", "Malicious package"),
    CRITICAL("critical", "Critical"),
    HIGH("high", "High"),
    MODERATE("moderate", "Moderate"),
    LOW("low", "Low"),
    UNRATED("unrated", "Unrated"),
}

data class CvssVector(val type: String, val vector: String)

data class VulnSeverity(val tier: VulnTier, val cvss: CvssVector?)

data class VulnFinding(
    val id: String,
    val component: VulnComponent,
    val malicious: Boolean = false,
    val severity: VulnSeverity? = null,
    val summary: String? = null,
    val details: String? = null,
    val fixedVersions: List<String> = emptyList(),
    val references: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val publishedIso: String? = null,
) {

    val tier: VulnTier
        get() = if (malicious) VulnTier.MALICIOUS else severity?.tier ?: VulnTier.UNRATED
}

data class VulnReport(
    val findings: List<VulnFinding>,
    val queried: Int,
    val asOfMillis: Long,
    val endpoint: String,
) {

    fun ordered(): List<VulnFinding> =
        findings.sortedWith(compareBy({ it.tier.ordinal }, { it.component.name }, { it.id }))

    fun tierCounts(): List<Pair<VulnTier, Int>> =
        VulnTier.entries.mapNotNull { tier ->
            findings.count { it.tier == tier }.takeIf { it > 0 }?.let { tier to it }
        }
}

enum class VulnViewState(val wire: String) {
    UNCONSENTED("unconsented"),
    WITHDRAWN("withdrawn"),
    NEVER("never"),
    SCANNING("scanning"),
    RESULTS("results"),
    OFFLINE("offline"),
    FAILED("failed"),
}

data class VulnSnapshot(
    val state: VulnViewState,
    val consent: VulnConsent,
    val endpoint: String,
    val manifests: List<String> = emptyList(),
    val ecosystems: List<String> = emptyList(),
    val componentCount: Int = 0,
    val done: Int = 0,
    val total: Int = 0,
    val report: VulnReport? = null,
    val silence: ScanSilence? = null,
)
