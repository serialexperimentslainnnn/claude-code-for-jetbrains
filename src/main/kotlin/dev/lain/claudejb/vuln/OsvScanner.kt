package dev.lain.claudejb.vuln

import com.intellij.openapi.diagnostic.logger
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI

internal class OsvScanner : VulnScanner {

    override val endpoint: String = VulnDisclosure.ENDPOINT

    override fun scan(inventory: List<VulnComponent>, listener: ScanListener): ScanAnswer {
        if (inventory.isEmpty()) return ScanAnswer.Silent(ScanSilence.NOTHING_TO_SCAN)

        val affected = ArrayList<VulnComponent>()
        var asked = 0
        for (batch in inventory.chunked(BATCH_SIZE)) {
            if (listener.cancelled()) return ScanAnswer.Silent(ScanSilence.CANCELLED)
            val body = when (val answer = OsvHttp.post(URI.create(VulnDisclosure.ENDPOINT), batchBody(batch))) {
                is OsvAnswer.Silent -> return ScanAnswer.Silent(answer.reason)
                is OsvAnswer.Body -> answer.json
            }
            val flags = OsvReplies.affectedFlags(body) ?: return ScanAnswer.Silent(ScanSilence.MALFORMED)
            flags.forEachIndexed { index, hit -> if (hit) batch.getOrNull(index)?.let(affected::add) }
            asked += batch.size
            listener.progress(asked, inventory.size)
        }

        if (affected.size > MAX_HYDRATED) {
            LOG.warn(
                "${affected.size} components came back affected and this build reads the first $MAX_HYDRATED; " +
                    "the rest are not shown",
            )
        }

        val findings = ArrayList<VulnFinding>()
        for (component in affected.take(MAX_HYDRATED)) {
            if (listener.cancelled()) return ScanAnswer.Silent(ScanSilence.CANCELLED)
            val body = when (val answer = OsvHttp.post(URI.create(QUERY_ENDPOINT), queryBody(component))) {
                is OsvAnswer.Silent -> return ScanAnswer.Silent(answer.reason)
                is OsvAnswer.Body -> answer.json
            }
            findings += OsvReplies.findings(body, component) ?: return ScanAnswer.Silent(ScanSilence.MALFORMED)
        }

        return ScanAnswer.Known(
            VulnReport(
                findings = findings,
                queried = inventory.size,
                asOfMillis = System.currentTimeMillis(),
                endpoint = VulnDisclosure.ENDPOINT,
            ),
        )
    }

    private fun batchBody(batch: List<VulnComponent>): String = buildJsonObject {
        put("queries", buildJsonArray { batch.forEach { add(queryOf(it)) } })
    }.toString()

    private fun queryBody(component: VulnComponent): String = queryOf(component).toString()

    private fun queryOf(component: VulnComponent) = buildJsonObject {
        put("version", component.version)
        put(
            "package",
            buildJsonObject {
                put("name", component.name)
                put("ecosystem", component.ecosystem)
            },
        )
    }

    private companion object {

        const val BATCH_SIZE = 500

        const val MAX_HYDRATED = 200

        const val QUERY_ENDPOINT = "https://api.osv.dev/v1/query"

        val LOG = logger<OsvScanner>()
    }
}
