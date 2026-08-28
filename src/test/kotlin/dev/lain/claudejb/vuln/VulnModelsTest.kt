package dev.lain.claudejb.vuln

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VulnModelsTest {

    private fun component(name: String) =
        VulnComponent("npm", name, "1.0.0", ComponentOrigin.UNKNOWN, "package-lock.json")

    private fun finding(
        id: String,
        name: String,
        malicious: Boolean = false,
        tier: VulnTier? = null,
    ) = VulnFinding(
        id = id,
        component = component(name),
        malicious = malicious,
        severity = tier?.let { VulnSeverity(it, CvssVector("CVSS_V3", "CVSS:3.1/AV:N/AC:L/PR:N/UI:N")) },
    )

    @Test
    fun `a malicious package carries no severity and is still its own tier`() {
        val malicious = finding("MAL-2024-1", "left-pad", malicious = true)

        assertNull(malicious.severity, "a severity here would be a score nobody published")
        assertEquals(VulnTier.MALICIOUS, malicious.tier)
    }

    @Test
    fun `malicious outranks critical, so ordering can never bury it`() {
        assertTrue(VulnTier.MALICIOUS.ordinal < VulnTier.CRITICAL.ordinal)
    }

    @Test
    fun `a finding with no severity at all is unrated, never dropped and never invented`() {
        val bare = finding("CVE-2024-2", "tinypool")

        assertNull(bare.severity)
        assertEquals(VulnTier.UNRATED, bare.tier)
    }

    @Test
    fun `ordering puts the malicious packages first and the unrated last`() {
        val report = VulnReport(
            findings = listOf(
                finding("CVE-1", "aaa", tier = VulnTier.LOW),
                finding("CVE-2", "bbb"),
                finding("CVE-3", "ccc", tier = VulnTier.CRITICAL),
                finding("MAL-1", "ddd", malicious = true),
            ),
            queried = 4,
            asOfMillis = 1_000L,
            endpoint = VulnDisclosure.ENDPOINT,
        )

        assertEquals(listOf("MAL-1", "CVE-3", "CVE-1", "CVE-2"), report.ordered().map { it.id })
    }

    @Test
    fun `the counts name only the tiers that actually occur, in tier order`() {
        val report = VulnReport(
            findings = listOf(
                finding("MAL-1", "aaa", malicious = true),
                finding("CVE-1", "bbb"),
                finding("CVE-2", "ccc"),
            ),
            queried = 3,
            asOfMillis = 1_000L,
            endpoint = VulnDisclosure.ENDPOINT,
        )

        assertEquals(listOf(VulnTier.MALICIOUS to 1, VulnTier.UNRATED to 2), report.tierCounts())
    }

    @Test
    fun `an origin the manifest cannot answer for is UNKNOWN rather than a guess`() {
        assertEquals("unknown", ComponentOrigin.UNKNOWN.wire)
        assertEquals(3, ComponentOrigin.entries.size)
        assertTrue(ComponentOrigin.entries.contains(ComponentOrigin.UNKNOWN))
    }

    @Test
    fun `consent is unasked until it is recorded, and an unknown word never reads as granted`() {
        assertEquals(VulnConsent.UNASKED, VulnConsent.from(null))
        assertEquals(VulnConsent.UNASKED, VulnConsent.from(""))
        assertEquals(VulnConsent.UNASKED, VulnConsent.from("yes"))
        assertEquals(VulnConsent.GRANTED, VulnConsent.from("granted"))
        assertEquals(VulnConsent.WITHDRAWN, VulnConsent.from("withdrawn"))
    }
}
