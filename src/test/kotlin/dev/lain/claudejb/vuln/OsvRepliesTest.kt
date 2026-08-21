package dev.lain.claudejb.vuln

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OsvRepliesTest {

    private val component = VulnComponent(
        ecosystem = "npm",
        name = "lodash",
        version = "4.17.20",
        origin = ComponentOrigin.DIRECT,
        manifest = "package-lock.json",
    )

    @Test
    fun `the batch answer keeps the order it was asked in, hit or miss`() {
        val flags = OsvReplies.affectedFlags(
            """{"results": [{"vulns": [{"id": "GHSA-1"}]}, {}, {"vulns": []}, {"vulns": [{"id": "GHSA-2"}]}]}""",
        )

        assertEquals(listOf(true, false, false, true), flags)
    }

    @Test
    fun `an answer this build cannot read is null, so the caller can stay silent`() {
        assertNull(OsvReplies.affectedFlags("""{"results": {"not": "a list"}}"""))
        assertNull(OsvReplies.affectedFlags("<html>502</html>"))
        assertNull(OsvReplies.findings("""[]""", component))
    }

    @Test
    fun `no vulnerability is a real answer, distinct from an unreadable one`() {
        assertEquals(emptyList<VulnFinding>(), OsvReplies.findings("""{"vulns": []}""", component))
        assertEquals(emptyList<Boolean>(), OsvReplies.affectedFlags("""{}"""))
    }

    @Test
    fun `a finding carries its severity, its fix and the component that pulled it in`() {
        val finding = OsvReplies.findings(ONE_VULN, component)!!.single()

        assertEquals("GHSA-p6mc-m468-83gg", finding.id)
        assertEquals(component, finding.component)
        assertEquals(VulnTier.HIGH, finding.tier)
        assertEquals("CVSS_V3", finding.severity?.cvss?.type)
        assertEquals(listOf("4.17.21"), finding.fixedVersions)
        assertEquals(listOf("CVE-2020-8203"), finding.aliases)
        assertEquals("Prototype pollution", finding.summary)
        assertFalse(finding.malicious)
    }

    @Test
    fun `a malicious package is read from its identifier, not from a severity it never carries`() {
        val finding = OsvReplies.findings(
            """{"vulns": [{"id": "MAL-2026-1234", "summary": "Malicious code in the package"}]}""",
            component,
        )!!.single()

        assertTrue(finding.malicious)
        assertEquals(VulnTier.MALICIOUS, finding.tier)
    }

    @Test
    fun `a severity word this build does not know leaves the finding unrated instead of guessing`() {
        val finding = OsvReplies.findings(
            """{"vulns": [{"id": "OSV-1", "database_specific": {"severity": "SPICY"}}]}""",
            component,
        )!!.single()

        assertEquals(VulnTier.UNRATED, finding.tier)
        assertNull(finding.severity)
    }

    private companion object {

        val ONE_VULN = """
            {"vulns": [{
              "id": "GHSA-p6mc-m468-83gg",
              "summary": "Prototype pollution",
              "details": "Versions before 4.17.21 are affected.",
              "aliases": ["CVE-2020-8203"],
              "published": "2026-05-06T16:07:00Z",
              "severity": [{"type": "CVSS_V3", "score": "CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:H/I:H/A:H"}],
              "affected": [{
                "ranges": [{"type": "SEMVER", "events": [{"introduced": "0"}, {"fixed": "4.17.21"}]}],
                "database_specific": {"severity": "HIGH"}
              }],
              "references": [{"type": "ADVISORY", "url": "https://github.com/advisories/GHSA-p6mc-m468-83gg"}]
            }]}
        """.trimIndent()
    }
}
