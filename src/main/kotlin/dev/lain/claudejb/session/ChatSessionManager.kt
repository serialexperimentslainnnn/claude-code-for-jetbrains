package dev.lain.claudejb.session

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class ChatSessionManager(private val project: Project) : Disposable {

    interface Listener {
        fun onSessionsChanged() {}
    }

    private val sessions = CopyOnWriteArrayList<ClaudeSession>()
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    var active: ClaudeSession? = null
        private set

    fun all(): List<ClaudeSession> = sessions.toList()

    fun addListener(listener: Listener) = listeners.add(listener)
    fun removeListener(listener: Listener) = listeners.remove(listener)

    @Synchronized
    fun create(): ClaudeSession = register(ClaudeSession(project, nextChatTitle()))

    private fun nextChatTitle(): String {
        val taken = sessions.mapNotNullTo(HashSet()) { session ->
            session.title.removePrefix(CHAT_TITLE_PREFIX).takeIf { it != session.title }?.toIntOrNull()
        }
        var n = 1
        while (n in taken) n++
        return "$CHAT_TITLE_PREFIX$n"
    }

    fun gitChat(): ClaudeSession? = sessions.firstOrNull { it.gitIntegration }

    @Synchronized
    fun gitChatOrCreate(): ClaudeSession {
        gitChat()?.let { return it }
        val previous = active
        val made = register(ClaudeSession(project, GIT_CHAT_TITLE, gitIntegration = true))
        previous?.let { setActive(it) }
        return made
    }

    private fun register(session: ClaudeSession): ClaudeSession {
        sessions.add(session)
        active = session
        fireChanged()
        return session
    }

    fun setActive(session: ClaudeSession?) {
        if (session != null && session !in sessions) return
        active = session
    }

    fun activeOrCreate(): ClaudeSession = active ?: create()

    fun remove(session: ClaudeSession) {
        if (!sessions.remove(session)) return
        session.dispose()
        if (active == session) active = sessions.lastOrNull()
        persistOpenTabs()
        fireChanged()
    }

    private fun persistOpenTabs() {
        SessionHistory.getInstance(project)
            .setOpenSessions(sessions.filterNot { it.gitIntegration }.mapNotNull { it.sessionId })
    }

    private fun fireChanged() = listeners.forEach { it.onSessionsChanged() }

    override fun dispose() {
        sessions.forEach { it.dispose() }
        sessions.clear()
    }

    companion object {
        const val GIT_CHAT_TITLE = "Git"

        private const val CHAT_TITLE_PREFIX = "Chat "

        fun getInstance(project: Project): ChatSessionManager = project.service()
    }
}
