package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class Highlight(val line: Int, val column: Int, val severity: String, val message: String, val inspection: String?)

internal object Severities {

    val PARAM = Param("severity", "Minimum severity: error, warning (default), weak or all", required = false)

    fun minimum(args: ToolArgs): HighlightSeverity? = when (val name = (args.optionalString("severity") ?: "warning").lowercase()) {
        "error" -> HighlightSeverity.ERROR
        "warning" -> HighlightSeverity.WARNING
        "weak" -> HighlightSeverity.WEAK_WARNING
        "all" -> null
        else -> throw ToolException("severity must be error, warning, weak or all, not " + name)
    }
}

internal class DaemonHighlights(private val project: Project) {

    suspend fun collect(file: VirtualFile, document: Document, minSeverity: HighlightSeverity?): List<Highlight>? {
        val analysed = CompletableDeferred<Unit>()
        val connection = project.messageBus.connect()
        try {
            val editors = withContext(Dispatchers.EDT) {
                val descriptor = OpenFileDescriptor(project, file).setUsePreviewTab(true)
                val opened = FileEditorManager.getInstance(project).openFileEditor(descriptor, false)
                connection.subscribe(
                    DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC,
                    object : DaemonCodeAnalyzer.DaemonListener {
                        override fun daemonFinished(fileEditors: Collection<FileEditor>) {
                            if (fileEditors.any { it in opened }) analysed.complete(Unit)
                        }
                    },
                )
                opened
            }
            if (editors.any { DaemonCodeAnalyzerEx.isHighlightingCompleted(it, project) }) analysed.complete(Unit)
            withTimeoutOrNull(ANALYSIS_TIMEOUT_MILLIS) { analysed.await() } ?: return null
        } finally {
            connection.disconnect()
        }
        return readAction {
            val out = ArrayList<Highlight>()
            DaemonCodeAnalyzerEx.processHighlights(document, project, minSeverity, 0, document.textLength) { info ->
                val description = info.description
                if (description != null) {
                    val line = document.getLineNumber(info.startOffset)
                    out += Highlight(
                        line + 1,
                        info.startOffset - document.getLineStartOffset(line) + 1,
                        info.severity.name,
                        description,
                        info.inspectionToolId,
                    )
                }
                true
            }
            out
        }
    }

    private companion object {
        const val ANALYSIS_TIMEOUT_MILLIS = 30_000L
    }
}
