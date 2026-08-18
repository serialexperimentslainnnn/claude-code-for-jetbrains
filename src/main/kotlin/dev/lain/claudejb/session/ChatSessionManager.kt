package dev.lain.claudejb.session

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Project-level owner of the open chat tabs. Each tab is one [ClaudeSession] (one `claude` process);
 * the manager tracks them, knows which one is active, and disposes them all with the project.
 *
 * The settings page and the info dialogs act on whatever session is [active], so opening a second chat
 * doesn't strand them on the first.
 */
@Service(Service.Level.PROJECT)
class ChatSessionManager(private val project: Project) : Disposable {

    /**
     * Notified when a session is registered or removed.
     *
     * It fires from inside [gitChatOrCreate]'s lock, so an implementation must not wait on another thread —
     * see that method. Its consumer is `ui.GitChatConversation`, which needs to hear about a Git chat created
     * by a caller that did not go through it.
     */
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

    /** Creates a fresh chat (does not start the process — the caller wires UI then calls [ClaudeSession.start]). */
    @Synchronized
    fun create(): ClaudeSession = register(ClaudeSession(project, nextChatTitle()))

    /**
     * The fallback title a new chat gets: `Chat <n>`, where **n is the lowest number no OPEN chat is using**.
     *
     * It was a monotonic counter, and that is the reported bug: closing the last chat opens a replacement
     * ([dev.lain.claudejb.ui.ChatTabsPanel.replaceLastChat]), so a session spent closing and reopening one chat
     * climbed to `Chat 47` while never having more than one — a number that counts nothing the user can see.
     * The count of chats ever created is not a fact about the chat in front of them.
     *
     * Lowest-free rather than `sessions.size + 1`, because the size says nothing about which names are taken:
     * with `Chat 1` and `Chat 2` open, closing the first and adding one would produce a second `Chat 2`, and two
     * identical pills in the row are indistinguishable — which is exactly the confusion this whole area is
     * already being debugged for.
     *
     * It reads the LIVE titles, so a number is freed by a rename or by the model naming that chat
     * ([SessionTitleReader]) as well as by a close. That is the intended reading: the number exists only to tell
     * unnamed chats apart, so a chat that has a real name is not holding one.
     *
     * `@Synchronized` on [create] rather than an atomic, because this is a check-then-act over [sessions] and a
     * thread-safe list does not make a pair of operations one — the same reason [gitChatOrCreate] holds the lock.
     */
    private fun nextChatTitle(): String {
        val taken = sessions.mapNotNullTo(HashSet()) { session ->
            session.title.removePrefix(CHAT_TITLE_PREFIX).takeIf { it != session.title }?.toIntOrNull()
        }
        var n = 1
        while (n in taken) n++
        return "$CHAT_TITLE_PREFIX$n"
    }

    /**
     * The Git integration's chat, if one exists.
     *
     * Held as a property of the session rather than a field here, so there is one answer and it cannot go stale:
     * a chat that went away is out of [sessions] already, and the next Git action makes a new one.
     */
    fun gitChat(): ClaudeSession? = sessions.firstOrNull { it.gitIntegration }

    /**
     * The Git integration's chat, made if the project does not have one — **the one door**, used by the Git
     * view's embedded conversation and by the gear-menu actions alike.
     *
     * A project has ONE Git conversation. A second would be a second `claude` process arguing with the first
     * about the same working tree, and the two would each hold half the context of what was asked.
     *
     * **It has no tab, and that is the change.** It was a tab in the row with the user's own chats, which put
     * its startup behind the full-window "Loading Claude Code" screen and meant a prompted action ran
     * somewhere they were not looking; it is drawn inside the Git view now. It is still a real conversation —
     * you can answer it (*"squash those two"*, *"not that file"*) and it carries on, which is the point of
     * prompting an agent rather than shelling out to `git` — and still a second process with its own context
     * and cost, with forced approval on every turn ([ClaudeSession.gitIntegration] owns that and the pinned
     * title).
     *
     * **Not persisted**: nothing records which id it was, so after a restart the project simply has none and
     * the next Git action makes one.
     *
     * Creating a session makes it the manager's ACTIVE one, and there is no tab selection coming to settle
     * that any more — so "the active chat", which is what every dialog outside the tool window asks for,
     * would silently become a conversation with no tab. Whatever was active stays active.
     *
     * **Synchronised because it is a check-then-act, and [sessions] being thread-safe does not make it one.**
     * A `CopyOnWriteArrayList` makes each operation atomic and says nothing about a pair of them: two callers
     * can both read no Git chat and both create one, and the second `claude` process is invisible — it is not
     * a tab, so nothing on screen would show it, and the two would each hold half the context of what was
     * asked while running commands against the same working tree. Every caller today is a UI gesture on the
     * EDT and would never collide; the lock is what stops that from being an unwritten precondition of a
     * `public` method with three call sites.
     *
     * It spans [fireChanged] deliberately, so a listener sees the new session rather than a moment in which
     * there is not one yet. Safe because no listener waits on another thread: the one that reaches back in
     * here (`ui.GitChatConversation`, which needs exactly this door covered — it is the creation path that
     * does not go through it) runs on this same thread and takes no lock of its own on the way.
     */
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

    /** The active session, creating the first one lazily if none exist yet (used by settings/dialogs). */
    fun activeOrCreate(): ClaudeSession = active ?: create()

    fun remove(session: ClaudeSession) {
        if (!sessions.remove(session)) return
        session.dispose()
        if (active == session) active = sessions.lastOrNull()
        persistOpenTabs()
        fireChanged()
    }

    /**
     * Keeps the persisted open-tab set in sync, so a closed tab is not restored on the next startup.
     *
     * **The Git conversation is excluded, and that is the whole reason this is a function.** It has no tab —
     * it is drawn inside the Git view — so persisting it means the next startup opens a chat tab for it,
     * which is precisely the thing removing the tab was for. It comes back as an ordinary chat, keeping its
     * real conversation, and the Git view makes a fresh one: recording it would mean a field in
     * [SessionHistory] for a label, which is the trade this feature already declined once.
     */
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
        /** What the Git integration's tab is called. Fixed, and pinned as such by [ClaudeSession.gitIntegration]. */
        const val GIT_CHAT_TITLE = "Git"

        /** The fallback title's prefix — written once, because [nextChatTitle] both builds it and parses it back. */
        private const val CHAT_TITLE_PREFIX = "Chat "

        fun getInstance(project: Project): ChatSessionManager = project.service()
    }
}
