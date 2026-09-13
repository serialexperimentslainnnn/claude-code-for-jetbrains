package dev.lain.claudejb.controller.bridge

import com.intellij.openapi.ide.CopyPasteManager
import dev.lain.claudejb.model.bridge.JcefBridge
import dev.lain.claudejb.model.bridge.Msg
import dev.lain.claudejb.view.window.JcefChatPanel
import java.awt.datatransfer.StringSelection

internal class BridgePrompting(private val panel: JcefChatPanel) {

    private val session get() = panel.session

    fun handle(m: Msg.Prompting) {
        when (m) {
            is Msg.Send -> if (m.scope == JcefBridge.SCOPE_GIT) panel.gitChat.send(m.text) else send(m.text)

            is Msg.Interrupt ->
                if (m.scope == JcefBridge.SCOPE_GIT) panel.gitChat.interrupt() else session.turnControl.interrupt()

            is Msg.RemoveQueued -> session.prompts.remove(m.index)

            is Msg.Copy -> CopyPasteManager.getInstance().setContents(StringSelection(m.text))
        }
    }

    private fun send(raw: String) {
        session.prompts.clearSuggestion()
        val attachments = panel.tray.take()
        val text = raw.trim()
        when {
            attachments.isEmpty() && text == "/login" -> session.login.start()

            attachments.isEmpty() && BTW.matches(text.substringBefore('\n')) ->
                session.sendSideQuestion(text.removePrefix("/btw").trim())

            else -> session.send(raw, attachments)
        }
    }

    private companion object {
        val BTW = Regex("^/btw\\b.*")
    }
}
