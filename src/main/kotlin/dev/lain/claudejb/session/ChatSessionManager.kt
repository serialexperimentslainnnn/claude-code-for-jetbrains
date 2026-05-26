package dev.lain.claudejb.session

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Project-level owner of the open chat tabs. Each tab is one [ClaudeSession] (one `claude` process);
 * the manager tracks them, knows which one is active, and disposes them all with the project.
 *
 * The settings page and the info dialogs act on whatever session is [active], so opening a second chat
 * doesn't strand them on the first.
 */
@Service(Service.Level.PROJECT)
class ChatSessionManager(private val project: Project) : Disposable {

    /** Notified when a session is created/removed so the tool window can sync its tabs. */
    interface Listener {
        fun onSessionsChanged() {}
    }

    private val sessions = CopyOnWriteArrayList<ClaudeSession>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val counter = AtomicInteger(0)

    @Volatile
    var active: ClaudeSession? = null
        private set

    fun all(): List<ClaudeSession> = sessions.toList()

    fun addListener(listener: Listener) = listeners.add(listener)
    fun removeListener(listener: Listener) = listeners.remove(listener)

    /** Creates a fresh chat (does not start the process — the caller wires UI then calls [ClaudeSession.start]). */
    fun create(): ClaudeSession {
        val session = ClaudeSession(project, "Chat ${counter.incrementAndGet()}")
        sessions.add(session)
        active = session
        fireChanged()
        return session
    }

    fun setActive(session: ClaudeSession?) {
        if (session != null && session !in sessions) return
        active = session
    }

    /** The active session, creating the first one lazily if none exist yet (used by settings/dialogs). */
    fun activeOrCreate(): ClaudeSession = active ?: create()

    fun remove(session: ClaudeSession) {
        if (!sessions.remove(session)) return
        session.dispose()
        if (active == session) active = sessions.lastOrNull()
        fireChanged()
    }

    private fun fireChanged() = listeners.forEach { it.onSessionsChanged() }

    override fun dispose() {
        sessions.forEach { it.dispose() }
        sessions.clear()
    }

    companion object {
        fun getInstance(project: Project): ChatSessionManager = project.service()
    }
}
