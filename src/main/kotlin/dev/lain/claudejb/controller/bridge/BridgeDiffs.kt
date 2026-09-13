package dev.lain.claudejb.controller.bridge

import com.intellij.openapi.application.ApplicationManager
import dev.lain.claudejb.controller.context.LinkResolver
import dev.lain.claudejb.model.bridge.Msg
import dev.lain.claudejb.model.diff.DiffPresenter
import dev.lain.claudejb.model.diff.EditSnapshot
import dev.lain.claudejb.model.permission.broker.PendingPermission
import dev.lain.claudejb.util.edt
import dev.lain.claudejb.util.thisLogger
import dev.lain.claudejb.view.diff.DiffEditors
import dev.lain.claudejb.view.payload.chat.JcefTranscriptPayload
import dev.lain.claudejb.view.window.JcefChatPanel

internal class BridgeDiffs(private val panel: JcefChatPanel) {

    private val log = thisLogger()

    fun handle(m: Msg.Diffs) {
        when (m) {
            is Msg.ViewDiff -> panel.cardSession(m.scope).cards.pending().firstOrNull { it.requestId == m.id }
                ?.let { viewPending(it) }

            is Msg.ViewDiffByTool -> snapshotAnywhere(m.toolUseId)
                ?.let { DiffEditors.openDiff(panel.project, it.toolName, it.input, it.beforeText) }

            is Msg.RevertEdit -> panel.edits.rewindOrRevert(m.toolUseId)

            is Msg.Open -> panel.links.open(m.url)

            is Msg.ResolveLinks -> resolveLinks(m)
        }
    }

    private fun viewPending(card: PendingPermission) {
        val path = DiffPresenter.filePathOf(card.input) ?: return
        val project = panel.project
        ApplicationManager.getApplication().executeOnPooledThread {
            val current = DiffPresenter.readCurrent(path, project.basePath)
            if (current == null) {
                log.warn("View diff refused: the card names a file outside the project or over the size cap")
                return@executeOnPooledThread
            }
            edt(project) { DiffEditors.openDiff(project, card.toolName, card.input, current) }
        }
    }

    private fun snapshotAnywhere(toolUseId: String): EditSnapshot? =
        panel.session.cards.editSnapshot(toolUseId) ?: panel.gitChat.session().cards.editSnapshot(toolUseId)

    private fun resolveLinks(m: Msg.ResolveLinks) {
        if (m.paths.isEmpty() && m.symbols.isEmpty()) return
        val project = panel.project
        panel.host.execBuilt("window.cc.links") {
            val resolved = LinkResolver.resolvePaths(project, m.paths) + LinkResolver.resolveSymbols(project, m.symbols)
            resolved.takeIf { it.isNotEmpty() }?.let { JcefTranscriptPayload.linksJson(m.rowId, it) }
        }
    }
}
