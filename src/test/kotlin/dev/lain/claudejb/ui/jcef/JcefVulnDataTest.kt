package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.vuln.ComponentOrigin
import dev.lain.claudejb.vuln.CvssVector
import dev.lain.claudejb.vuln.ScanSilence
import dev.lain.claudejb.vuln.VulnComponent
import dev.lain.claudejb.vuln.VulnConsent
import dev.lain.claudejb.vuln.VulnDisclosure
import dev.lain.claudejb.vuln.VulnFinding
import dev.lain.claudejb.vuln.VulnReport
import dev.lain.claudejb.vuln.VulnSeverity
import dev.lain.claudejb.vuln.VulnSnapshot
import dev.lain.claudejb.vuln.VulnTier
import dev.lain.claudejb.vuln.VulnViewState
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JcefVulnDataTest {

    private val component = VulnComponent("npm", "left-pad", "1.3.0", ComponentOrigin.DIRECT, "package-lock.json")

    private fun snapshot(
        state: VulnViewState,
        consent: VulnConsent = VulnConsent.GRANTED,
        report: VulnReport? = null,
        silence: ScanSilence? = null,
        done: Int = 0,
        total: Int = 0,
    ) = VulnSnapshot(
        state = state,
        consent = consent,
        endpoint = VulnDisclosure.ENDPOINT,
        manifests = listOf("package-lock.json"),
        ecosystems = listOf("npm"),
        componentCount = 412,
        done = done,
        total = total,
        report = report,
        silence = silence,
    )

    private fun report(vararg findings: VulnFinding) =
        VulnReport(findings.toList(), queried = 412, asOfMillis = 1_000L, endpoint = VulnDisclosure.ENDPOINT)

    private fun json(snapshot: VulnSnapshot?, now: Long = 1_000L): JsonObject? =
        JcefVulnData.vulnJson(snapshot, now)

    private fun word(obj: JsonObject, key: String): String? =
        obj[key]?.takeIf { it != JsonNull }?.jsonPrimitive?.content

    @Test
    fun `no snapshot draws no card at all`() {
        assertNull(json(null))
    }

    @Test
    fun `before consent the payload names the destination and carries no result`() {
        val obj = json(snapshot(VulnViewState.UNCONSENTED, consent = VulnConsent.UNASKED))!!

        assertEquals("unconsented", word(obj, "state"))
        assertEquals("unasked", word(obj, "consent"))
        assertEquals("stopped", word(obj, "status"))
        assertEquals(VulnDisclosure.ENDPOINT, word(obj, "endpoint"))
        assertEquals(VulnDisclosure.OPERATOR, word(obj, "operator"))
        assertEquals(JsonNull, obj["report"])
        assertTrue(obj["disclosure"]!!.jsonObject["sent"]!!.jsonArray.isNotEmpty())
        assertTrue(obj["disclosure"]!!.jsonObject["caveats"]!!.jsonArray.isNotEmpty())
        assertEquals(412, obj["inventory"]!!.jsonObject["components"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a scan in flight paints running and says how far it has got`() {
        val obj = json(snapshot(VulnViewState.SCANNING, done = 40, total = 412))!!

        assertEquals("scanning", word(obj, "state"))
        assertEquals("running", word(obj, "status"))
        assertEquals(40, obj["progress"]!!.jsonObject["done"]!!.jsonPrimitive.int)
        assertEquals(412, obj["progress"]!!.jsonObject["total"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a cancelled scan is stopped, not failed`() {
        val obj = json(snapshot(VulnViewState.FAILED, silence = ScanSilence.CANCELLED))!!

        assertEquals("stopped", word(obj, "status"))
        assertEquals("cancelled", word(obj, "reason"))
        assertEquals(ScanSilence.CANCELLED.note, word(obj, "note"))
    }

    @Test
    fun `a scan that could not reach the database is failed and says so in words`() {
        val obj = json(snapshot(VulnViewState.FAILED, silence = ScanSilence.UNREACHABLE))!!

        assertEquals("failed", word(obj, "status"))
        assertEquals("unreachable", word(obj, "reason"))
    }

    @Test
    fun `offline keeps the last result and states how old it is`() {
        val finding = VulnFinding(id = "CVE-1", component = component)
        val obj = json(
            snapshot(VulnViewState.OFFLINE, report = report(finding), silence = ScanSilence.UNREACHABLE),
            now = 61_000L,
        )!!

        assertEquals("offline", word(obj, "state"))
        assertEquals("stopped", word(obj, "status"))
        val result = obj["report"]!!.jsonObject
        assertEquals(1_000L, result["asOfMillis"]!!.jsonPrimitive.long)
        assertEquals(60_000L, result["ageMillis"]!!.jsonPrimitive.long)
    }

    @Test
    fun `a malicious package is sent as its own tier with no score of any kind`() {
        val malicious = VulnFinding(id = "MAL-1", component = component, malicious = true)
        val obj = json(snapshot(VulnViewState.RESULTS, report = report(malicious)))!!

        assertEquals("completed", word(obj, "status"))
        val first = obj["report"]!!.jsonObject["findings"]!!.jsonArray.first().jsonObject
        assertEquals("malicious", first["tier"]!!.jsonPrimitive.content)
        assertEquals("Malicious package", first["tierLabel"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, first["cvss"], "OSV publishes no score for these and neither do we")
        assertEquals(JsonNull, first["cvssType"])
    }

    @Test
    fun `a rated finding carries the vector string it was given and no number beside it`() {
        val rated = VulnFinding(
            id = "CVE-2",
            component = component,
            severity = VulnSeverity(VulnTier.HIGH, CvssVector("CVSS_V3", "CVSS:3.1/AV:N/AC:L/PR:N/UI:N")),
        )
        val obj = json(snapshot(VulnViewState.RESULTS, report = report(rated)))!!
        val first = obj["report"]!!.jsonObject["findings"]!!.jsonArray.first().jsonObject

        assertEquals("CVSS:3.1/AV:N/AC:L/PR:N/UI:N", first["cvss"]!!.jsonPrimitive.content)
        assertEquals("CVSS_V3", first["cvssType"]!!.jsonPrimitive.content)
        assertNull(first["score"], "there is no numeric score in OSV, so the page must never be sent one")
    }

    @Test
    fun `the origin travels as a word and as the sentence the view prints`() {
        val unknown = VulnFinding(
            id = "CVE-3",
            component = component.copy(origin = ComponentOrigin.UNKNOWN),
        )
        val obj = json(snapshot(VulnViewState.RESULTS, report = report(unknown)))!!
        val first = obj["report"]!!.jsonObject["findings"]!!.jsonArray.first().jsonObject

        assertEquals("unknown", first["origin"]!!.jsonPrimitive.content)
        assertEquals(ComponentOrigin.UNKNOWN.label, first["originLabel"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an advisory reference that is not a web address never reaches the page`() {
        val hostile = VulnFinding(
            id = "CVE-4",
            component = component,
            references = listOf(
                "https://example.invalid/advisory",
                "javascript:alert(1)",
                "data:text/html,<script>alert(1)</script>",
            ),
        )
        val obj = json(snapshot(VulnViewState.RESULTS, report = report(hostile)))!!
        val refs = obj["report"]!!.jsonObject["findings"]!!.jsonArray.first().jsonObject["references"]!!.jsonArray

        assertEquals(listOf("https://example.invalid/advisory"), refs.map { it.jsonPrimitive.content })
    }

    @Test
    fun `a very long result is capped and says how much of it is on screen`() {
        val many = (1..JcefVulnData.MAX_FINDINGS + 25).map {
            VulnFinding(id = "CVE-$it", component = component.copy(name = "pkg$it"))
        }
        val obj = json(snapshot(VulnViewState.RESULTS, report = report(*many.toTypedArray())))!!
        val result = obj["report"]!!.jsonObject

        assertEquals(many.size, result["total"]!!.jsonPrimitive.int)
        assertEquals(JcefVulnData.MAX_FINDINGS, result["shown"]!!.jsonPrimitive.int)
        assertEquals(JcefVulnData.MAX_FINDINGS, result["findings"]!!.jsonArray.size)
    }

    @Test
    fun `the literal list is the components themselves, with the destination beside them`() {
        val obj = JcefVulnData.inventoryJson(listOf(component), VulnDisclosure.ENDPOINT)

        assertEquals(VulnDisclosure.ENDPOINT, obj["endpoint"]!!.jsonPrimitive.content)
        assertEquals(1, obj["total"]!!.jsonPrimitive.int)
        assertEquals(false, obj["truncated"]!!.jsonPrimitive.boolean)
        val row = obj["components"]!!.jsonArray.first().jsonObject
        assertEquals("npm", row["ecosystem"]!!.jsonPrimitive.content)
        assertEquals("left-pad", row["name"]!!.jsonPrimitive.content)
        assertEquals("1.3.0", row["version"]!!.jsonPrimitive.content)
        assertEquals("direct", row["origin"]!!.jsonPrimitive.content)
        assertNotNull(row["manifest"])
    }
}
