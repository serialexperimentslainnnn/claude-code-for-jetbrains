package dev.lain.claudejb.controller.vuln

import dev.lain.claudejb.model.vuln.ComponentOrigin
import dev.lain.claudejb.model.vuln.ScanAnswer
import dev.lain.claudejb.model.vuln.ScanSilence
import dev.lain.claudejb.model.vuln.VulnComponent
import dev.lain.claudejb.model.vuln.VulnDisclosure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class OsvScannerTest {

    private val posted = ArrayList<Pair<String, String>>()

    private fun component(name: String) = VulnComponent("npm", name, "1.0.0", ComponentOrigin.DIRECT, "package-lock.json")

    private fun batchReply(vararg hit: Boolean) =
        hit.joinToString(",", """{"results":[""", "]}") { if (it) """{"vulns":[{"id":"GHSA-1"}]}""" else """{"vulns":[]}""" }

    private val queryReply = """{"vulns":[{"id":"GHSA-1","summary":"bad","affected":[{"ranges":[{"events":[{"fixed":"1.0.1"}]}]}]}]}"""

    private fun scanner(answer: (URI, String) -> OsvAnswer) = OsvScanner { uri, body ->
        posted += uri.toString() to body
        answer(uri, body)
    }

    private fun byEndpoint(batch: OsvAnswer, query: OsvAnswer): (URI, String) -> OsvAnswer =
        { uri, _ -> if (uri.toString() == VulnDisclosure.ENDPOINT) batch else query }

    @Test
    fun `an empty inventory asks nothing and says so`() {
        val answer = scanner { _, _ -> error("nothing may be sent") }.scan(emptyList(), { _, _ -> }, { false })
        assertEquals(ScanAnswer.Silent(ScanSilence.NOTHING_TO_SCAN), answer)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun `the affected components of the batch are hydrated one by one into findings`() {
        val progress = ArrayList<Pair<Int, Int>>()
        val answer = scanner(byEndpoint(OsvAnswer.Body(batchReply(true, false)), OsvAnswer.Body(queryReply)))
            .scan(listOf(component("left-pad"), component("chalk")), { done, total -> progress += done to total }, { false })
        val report = (answer as ScanAnswer.Known).report
        assertEquals(1, report.findings.size)
        assertEquals("GHSA-1", report.findings.single().id)
        assertEquals("left-pad", report.findings.single().component.name)
        assertEquals(listOf("1.0.1"), report.findings.single().fixedVersions)
        assertEquals(2, report.queried)
        assertEquals(VulnDisclosure.ENDPOINT, report.endpoint)
        assertEquals(listOf(2 to 2), progress)
        assertEquals(2, posted.size)
        assertTrue(posted[0].second.contains("\"queries\"") && posted[0].second.contains("left-pad") && posted[0].second.contains("chalk"))
        assertTrue(posted[1].first.endsWith("/v1/query") && posted[1].second.contains("left-pad"))
    }

    @Test
    fun `a batch of nothing affected is a report with no findings`() {
        val answer = scanner(byEndpoint(OsvAnswer.Body(batchReply(false)), OsvAnswer.Body(queryReply)))
            .scan(listOf(component("chalk")), { _, _ -> }, { false })
        assertTrue((answer as ScanAnswer.Known).report.findings.isEmpty())
        assertEquals(1, posted.size)
    }

    @Test
    fun `the database's silence is passed on, from the batch and from a query`() {
        val fromBatch = scanner(byEndpoint(OsvAnswer.Silent(ScanSilence.UNREACHABLE), OsvAnswer.Body(queryReply)))
            .scan(listOf(component("a")), { _, _ -> }, { false })
        assertEquals(ScanAnswer.Silent(ScanSilence.UNREACHABLE), fromBatch)
        val fromQuery = scanner(byEndpoint(OsvAnswer.Body(batchReply(true)), OsvAnswer.Silent(ScanSilence.REFUSED)))
            .scan(listOf(component("a")), { _, _ -> }, { false })
        assertEquals(ScanAnswer.Silent(ScanSilence.REFUSED), fromQuery)
    }

    @Test
    fun `an answer this build cannot read is malformed, from the batch and from a query`() {
        val fromBatch = scanner(byEndpoint(OsvAnswer.Body("<html>"), OsvAnswer.Body(queryReply)))
            .scan(listOf(component("a")), { _, _ -> }, { false })
        assertEquals(ScanAnswer.Silent(ScanSilence.MALFORMED), fromBatch)
        val fromQuery = scanner(byEndpoint(OsvAnswer.Body(batchReply(true)), OsvAnswer.Body("nope")))
            .scan(listOf(component("a")), { _, _ -> }, { false })
        assertEquals(ScanAnswer.Silent(ScanSilence.MALFORMED), fromQuery)
    }

    @Test
    fun `cancelling stops before the batch and between the queries`() {
        val beforeBatch = scanner { _, _ -> error("cancelled first") }.scan(listOf(component("a")), { _, _ -> }, { true })
        assertEquals(ScanAnswer.Silent(ScanSilence.CANCELLED), beforeBatch)
        var calls = 0
        val betweenQueries = scanner(byEndpoint(OsvAnswer.Body(batchReply(true)), OsvAnswer.Body(queryReply)))
            .scan(listOf(component("a")), { _, _ -> }, { calls++ >= 1 })
        assertEquals(ScanAnswer.Silent(ScanSilence.CANCELLED), betweenQueries)
    }

    @Test
    fun `more affected components than this build hydrates are cut, and the rest are not asked about`() {
        val inventory = (1..201).map { component("pkg$it") }
        val flags = BooleanArray(201) { true }
        val answer = scanner(byEndpoint(OsvAnswer.Body(batchReply(*flags)), OsvAnswer.Body(queryReply)))
            .scan(inventory, { _, _ -> }, { false })
        assertEquals(200, (answer as ScanAnswer.Known).report.findings.size)
        assertEquals(201, posted.size)
    }
}
