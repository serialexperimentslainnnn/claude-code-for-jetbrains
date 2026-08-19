package dev.lain.claudejb.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.SessionListener
import dev.lain.claudejb.session.TranscriptEntry
import dev.lain.claudejb.session.TranscriptModel
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.ui.jcef.JcefBridge
import dev.lain.claudejb.ui.jcef.JcefCardPayload
import dev.lain.claudejb.ui.jcef.JcefTranscriptPayload
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
internal class GitChatConversation(private val project: Project) :
    SessionListener, TranscriptModel.Listener, ChatSessionManager.Listener {

    internal interface View {
        fun drawGitChat(payload: String?)

        fun refreshGitChatPermissions()
    }

    private val views = CopyOnWriteArrayList<View>()

    private var attached: ClaudeSession? = null

    init {
        ChatSessionManager.getInstance(project).addListener(this)
    }

    private fun current(): ClaudeSession? {
        val chat = ChatSessionManager.getInstance(project).gitChat()
        if (chat !== attached) {
            attached?.let {
                it.transcript.removeListener(this)
                it.removeListener(this)
            }
            attached = chat
            chat?.let {
                it.transcript.addListener(this)
                it.addListener(this)
            }
        }
        return chat
    }

    @Synchronized
    fun sessionOrCreate(): ClaudeSession {
        current()?.let { return it }
        val chat = ChatSessionManager.getInstance(project).gitChatOrCreate()
        current()
        ClaudeSettings.getInstance(project).applyTo(chat)
        chat.start()
        broadcast()
        return chat
    }

    fun send(text: String) {
        if (text.isBlank()) return
        sessionOrCreate().send(text)
    }

    fun interrupt() {
        current()?.interrupt()
    }

    fun permissionGroup(): List<JcefCardPayload.Group> {
        val pending = current()?.cards?.pending().orEmpty()
        return if (pending.isEmpty()) emptyList() else listOf(JcefCardPayload.Group(pending, JcefBridge.SCOPE_GIT))
    }

    fun attach(view: View) {
        views.addIfAbsent(view)
        view.drawGitChat(payload())
    }

    fun detach(view: View) {
        views.remove(view)
    }

    private fun payload(): String? {
        val chat = current() ?: return null
        return buildString {
            append("{\"running\":").append(chat.isRunning())
            append(",\"starting\":").append(chat.isStarting())
            append(",\"turnActive\":").append(chat.turnActive)
            append(",\"rows\":").append(JcefTranscriptPayload.batchJson(rows(chat)))
            append("}")
        }
    }

    private fun rows(chat: ClaudeSession): List<Pair<TranscriptEntry, Int>> =
        chat.transcript.entries.mapIndexed { index, entry -> entry to index }

    private fun broadcast() {
        val json = payload()
        views.forEach { it.drawGitChat(json) }
    }

    override fun onAdded(entry: TranscriptEntry, index: Int) = broadcast()

    override fun onUpdated(entry: TranscriptEntry) = broadcast()

    override fun onCleared() = broadcast()

    override fun onStateChanged() = broadcast()

    override fun onSessionsChanged() = broadcast()

    override fun onPermissionsChanged() {
        views.forEach { it.refreshGitChatPermissions() }
    }

    companion object {
        fun getInstance(project: Project): GitChatConversation = project.service()
    }
}
