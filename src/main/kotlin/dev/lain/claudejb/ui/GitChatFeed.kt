package dev.lain.claudejb.ui

import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.ui.jcef.JcefCardPayload

internal class GitChatFeed(
    private val panel: JcefChatPanel,
    private val exec: (String) -> Unit,
) : GitChatConversation.View {

    private val conversation = GitChatConversation.getInstance(panel.project)

    private var lastPushed: String? = null

    init {
        conversation.attach(this)
    }

    fun session(): ClaudeSession = conversation.sessionOrCreate()

    fun send(text: String) = conversation.send(text)

    fun interrupt() = conversation.interrupt()

    fun permissionGroup(): List<JcefCardPayload.Group> = conversation.permissionGroup()

    fun show() {
        exec("window.CC && CC.dash && CC.dash.setGitSubView && CC.dash.setGitSubView('chat')")
        exec("window.cc.showGitView && window.cc.showGitView()")
    }

    override fun drawGitChat(payload: String?) {
        val json = payload ?: "null"
        if (json == lastPushed) return
        lastPushed = json
        exec("window.cc.gitChat && window.cc.gitChat($json)")
    }

    override fun refreshGitChatPermissions() = panel.pushPermissions()

    fun dispose() = conversation.detach(this)
}
