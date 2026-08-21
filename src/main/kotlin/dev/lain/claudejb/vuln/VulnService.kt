package dev.lain.claudejb.vuln

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.lain.claudejb.settings.ClaudeSettings
import java.io.File

@Service(Service.Level.PROJECT)
internal class VulnService(private val project: Project) {

    var scanner: VulnScanner? = null

    @Volatile
    private var components: List<VulnComponent> = emptyList()

    @Volatile
    private var cancelRequested: Boolean = false

    private var manifests: List<String> = emptyList()
    private var ecosystems: List<String> = emptyList()
    private var collecting: Boolean = false
    private var collected: Boolean = false
    private var scanning: Boolean = false
    private var report: VulnReport? = null
    private var silence: ScanSilence? = null
    private var done: Int = 0
    private var total: Int = 0

    fun consent(): VulnConsent = VulnConsent.from(ClaudeSettings.getInstance(project).state.vulnConsent)

    fun inventory(): List<VulnComponent> = components

    fun finding(id: String): VulnFinding? = report?.findings?.firstOrNull { it.id == id }

    fun snapshot(): VulnSnapshot = VulnSnapshot(
        state = viewState(),
        consent = consent(),
        endpoint = scanner?.endpoint ?: VulnDisclosure.ENDPOINT,
        manifests = manifests,
        ecosystems = ecosystems,
        componentCount = components.size,
        done = done,
        total = total,
        report = report,
        silence = silence,
    )

    fun setConsent(granted: Boolean, onChanged: () -> Unit) {
        val next = if (granted) VulnConsent.GRANTED else VulnConsent.WITHDRAWN
        ClaudeSettings.getInstance(project).update { it.vulnConsent = next.wire }
        if (!granted) {
            cancelRequested = true
            report = null
            silence = null
            done = 0
            total = 0
        }
        onChanged()
    }

    fun refresh(onChanged: () -> Unit) {
        if (collecting || collected) return
        val root = projectRoot() ?: return
        collecting = true
        ApplicationManager.getApplication().executeOnPooledThread {
            val found = collectFrom(root)
            edt {
                collecting = false
                collected = true
                adopt(found)
                onChanged()
            }
        }
    }

    fun scan(onChanged: () -> Unit) {
        if (scanning) return
        if (consent() != VulnConsent.GRANTED) return settle(ScanSilence.NO_CONSENT, onChanged)
        val root = projectRoot() ?: return settle(ScanSilence.NOTHING_TO_SCAN, onChanged)
        val engine = scanner
        scanning = true
        cancelRequested = false
        silence = null
        done = 0
        total = 0
        onChanged()
        ApplicationManager.getApplication().executeOnPooledThread {
            val items = collectFrom(root)
            edt {
                collected = true
                adopt(items)
                total = items.size
                onChanged()
            }
            finish(runScan(engine, items, onChanged), onChanged)
        }
    }

    fun cancel(onChanged: () -> Unit) {
        cancelRequested = true
        if (!scanning) return
        onChanged()
    }

    private fun runScan(engine: VulnScanner?, items: List<VulnComponent>, onChanged: () -> Unit): ScanAnswer = when {
        items.isEmpty() -> ScanAnswer.Silent(ScanSilence.NOTHING_TO_SCAN)

        engine == null -> ScanAnswer.Silent(ScanSilence.NO_SCANNER)

        cancelRequested -> ScanAnswer.Silent(ScanSilence.CANCELLED)

        else -> runCatching { engine.scan(items, listener(onChanged)) }.getOrElse {
            LOG.warn("The vulnerability scanner threw; treating it as an unreadable answer", it)
            ScanAnswer.Silent(ScanSilence.MALFORMED)
        }
    }

    private fun listener(onChanged: () -> Unit): ScanListener = object : ScanListener {

        override fun progress(done: Int, total: Int) = edt {
            this@VulnService.done = done
            this@VulnService.total = total
            onChanged()
        }

        override fun cancelled(): Boolean = cancelRequested
    }

    private fun finish(answer: ScanAnswer, onChanged: () -> Unit) = edt {
        scanning = false
        when (answer) {
            is ScanAnswer.Known -> {
                report = answer.report
                silence = null
                done = answer.report.queried
                total = answer.report.queried
            }

            is ScanAnswer.Silent -> silence = answer.reason
        }
        onChanged()
    }

    private fun settle(reason: ScanSilence, onChanged: () -> Unit) {
        silence = reason
        onChanged()
    }

    private fun adopt(found: List<VulnComponent>) {
        components = found
        manifests = found.map { it.manifest }.distinct().sorted()
        ecosystems = found.map { it.ecosystem }.distinct().sorted()
    }

    private fun collectFrom(root: File): List<VulnComponent> = runCatching { VulnInventory.collect(root) }
        .getOrElse {
            LOG.warn("Could not read the dependency manifests of ${project.name}", it)
            emptyList()
        }

    private fun projectRoot(): File? = project.basePath?.let(::File)?.takeIf { it.isDirectory }

    private fun viewState(): VulnViewState = when {
        consent() == VulnConsent.UNASKED -> VulnViewState.UNCONSENTED
        consent() == VulnConsent.WITHDRAWN -> VulnViewState.WITHDRAWN
        scanning -> VulnViewState.SCANNING
        report != null && silence == null -> VulnViewState.RESULTS
        report != null -> VulnViewState.OFFLINE
        silence != null -> VulnViewState.FAILED
        else -> VulnViewState.NEVER
    }

    private fun edt(block: () -> Unit) = ApplicationManager.getApplication().invokeLater({
        if (!project.isDisposed) block()
    }, ModalityState.any())

    companion object {

        fun getInstance(project: Project): VulnService = project.service()

        private val LOG = logger<VulnService>()
    }
}
