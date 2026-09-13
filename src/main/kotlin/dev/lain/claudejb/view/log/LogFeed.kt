package dev.lain.claudejb.view.log

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.SystemInfo
import dev.lain.claudejb.util.LogRing
import dev.lain.claudejb.util.PluginIdentity
import dev.lain.claudejb.util.PluginLog
import dev.lain.claudejb.util.edt
import dev.lain.claudejb.util.logger
import dev.lain.claudejb.view.log.JcefLogData
import dev.lain.claudejb.view.window.JcefChatPanel
import java.awt.datatransfer.StringSelection

internal class LogFeed(private val panel: JcefChatPanel) {

    fun push(since: Long) = offEdt {
        val json = JcefLogData.logJson(LogRing.since(since), PluginLog.debugOn)
        edt(panel.project) { panel.host.exec("window.cc.log && window.cc.log($json)") }
    }

    fun setDebug(on: Boolean) = PluginLog.setDebug(on)

    fun copy(level: String) {
        val binary = panel.session.catalog.binaryVersion ?: "unknown"
        offEdt {
            val lines = LogRing.snapshot(JcefLogData.levelOf(level))
            val text = JcefLogData.reportText(lines, header(binary, level, lines.size, LogRing.since(-1).dropped))
            edt(panel.project) { CopyPasteManager.getInstance().setContents(StringSelection(text)) }
        }
    }

    private fun header(binary: String, level: String, count: Int, dropped: Long): List<String> {
        val app = ApplicationInfo.getInstance()
        return listOf(
            "Claude Code Native ${PluginIdentity.PLUGIN_VERSION}",
            "${app.fullApplicationName} (build ${app.build.asString()})",
            "${SystemInfo.OS_NAME} ${SystemInfo.OS_VERSION} ${SystemInfo.OS_ARCH} · JRE ${SystemInfo.JAVA_RUNTIME_VERSION}",
            "claude $binary",
            "debug ${if (PluginLog.debugOn) "on" else "off"} · filter $level · $count line(s), $dropped dropped from the ring",
        )
    }

    private fun offEdt(block: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching(block).onFailure { log.warn("Claude Code could not answer the Log view", it) }
        }
    }

    private companion object {
        private val log = logger<LogFeed>()
    }
}
