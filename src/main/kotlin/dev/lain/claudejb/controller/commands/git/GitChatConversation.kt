package dev.lain.claudejb.controller.commands.git

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.lain.claudejb.controller.session.ChatSessionManager
import dev.lain.claudejb.controller.session.ClaudeSession
import dev.lain.claudejb.controller.session.SessionListener
import dev.lain.claudejb.model.bridge.JcefBridge
import dev.lain.claudejb.model.session.launch.LaunchOptions
import dev.lain.claudejb.model.session.transcript.TranscriptEntry
import dev.lain.claudejb.model.session.transcript.TranscriptModel
import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.view.payload.chat.JcefCardPayload
import dev.lain.claudejb.view.payload.chat.JcefTranscriptPayload
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
internal class GitChatConversation(private val project: Project) :
    SessionListener, TranscriptModel.Listener {

    internal interface View {
        fun drawGitChat(payload: String?)

        fun refreshGitChatPermissions()
    }

    private val views = CopyOnWriteArrayList<View>()

    private var attached: ClaudeSession? = null

    init {
        ChatSessionManager.getInstance(project).addListener(::broadcast)
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
        chat.settings.adopt(LaunchOptions.from(ClaudeSettings.getInstance(project)))
        chat.start()
        broadcast()
        return chat
    }

    fun send(text: String) {
        if (text.isBlank()) return
        sessionOrCreate().send(text)
    }

    fun interrupt() {
        current()?.turnControl?.interrupt()
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
            append(",\"starting\":").append(chat.lifecycle.isStarting())
            append(",\"turnActive\":").append(chat.turn.active)
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

    override fun onPermissionsChanged() {
        views.forEach { it.refreshGitChatPermissions() }
    }

    companion object {
        fun getInstance(project: Project): GitChatConversation = project.service()
    }
}
