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

/**
 * The project's ONE Git conversation, and the only thing that listens to it.
 *
 * **What this service exists to make impossible.** The conversation was already single — [ChatSessionManager]
 * find-or-creates it and returns the same [ClaudeSession] to everyone — but the SUBSCRIPTION to it was not.
 * Every open chat built a [GitChatFeed] that attached to the session only as a side effect of *acting* on it
 * (typing a Git-scoped turn, answering one of its cards, pressing a prompted action), so a page that merely
 * *looked* at the Git view had no listener, had never been sent a payload, and drew nothing at all. The pane
 * is empty and unlabelled in that state, which reads exactly like a brand-new conversation — and going back
 * to the chat that did act showed the real one still whole, because it was never anything but one session.
 *
 * The rule that follows, and it is the whole design: **the conversation, its turn state and its cards belong
 * to the project; the only thing that belongs to a panel is where they are painted.** A [View] registers
 * itself, is handed the WHOLE conversation immediately, and is handed it again on every change. Nothing about
 * what the conversation *is* may move back into a per-panel field — a second copy of it is how the defect
 * returns the next time somebody adds a state to it.
 *
 * **Its cards travel with it.** Every turn here runs with forced approval ([ClaudeSession.gitIntegration]), so
 * a `git commit` stops and waits. If that card only reached the page that started the turn, switching chat
 * while one was up would leave the user with a conversation they cannot finish from where they are standing —
 * so a change to the pending set rebuilds the permission region of every page ([View.refreshGitChatPermissions]).
 *
 * **Lazily created, silently started.** Looking ATTACHES; only acting CREATES. The session is a second
 * `claude` process with its own context and cost, so nothing here spawns one to draw an empty pane, and there
 * is no tab, hence no full-window boot screen for a chat nobody opened — the wait is stated inside the pane
 * itself (`starting` in the payload).
 *
 * It deliberately does not implement [SessionListener.onAttention]: a background chat asking for attention
 * badges its tab and raises a notification, and this one has no tab. The card is already on screen in the
 * view the user pressed the button in, in every page at once.
 *
 * EDT-confined: both listener sets fire there and every caller is a UI gesture.
 */
@Service(Service.Level.PROJECT)
internal class GitChatConversation(private val project: Project) :
    SessionListener, TranscriptModel.Listener, ChatSessionManager.Listener {

    /**
     * A page that draws this conversation. One per open chat — the same conversation, several browsers.
     *
     * Both callbacks are pushes, never pulls: a view that had to ask would have to know when to, and "when to"
     * is exactly the knowledge that was missing.
     */
    internal interface View {
        /** Draw the WHOLE conversation — never a delta — or `null` while the project has no Git chat yet. */
        fun drawGitChat(payload: String?)

        /** Rebuild the page's one permission region: a card of this conversation's appeared or was answered. */
        fun refreshGitChatPermissions()
    }

    private val views = CopyOnWriteArrayList<View>()

    /**
     * The session this service currently holds its listeners on.
     *
     * **Not a second answer to "which is the Git chat"** — [ChatSessionManager] owns that, and this field only
     * records where the listeners were put, so [current] can move them when the manager's answer changes
     * (the manager disposes its sessions with the project, and a test fixture reuses the project across tests).
     */
    private var attached: ClaudeSession? = null

    // Declared BELOW both fields, and that is the repository's standing rule rather than a preference here:
    // Kotlin runs property initializers and `init` blocks in declaration order, so an `init` above them
    // publishes `this` to a listener registry while `views` and `attached` are still null. Nothing fires
    // synchronously from `addListener` today, which is exactly what makes the inverted order survive review
    // and fail later — the compiler only flags a DIRECT reference, never one made through a callback.
    // `InitOrderContractTest` scans the sources for this.
    init {
        // **The conversation can appear without this service creating it.** The gear menu's prompted actions
        // fall back to `ChatSessionManager.gitChatOrCreate()` when there is no chat panel to go through, and
        // that door registers a session nobody here would ever hear about: no listener, no payload, and every
        // open page drawing an empty pane over a conversation that is already running — the exact defect this
        // service exists to close, re-entering through the one path that bypasses it. Listening to the manager
        // is what makes the coverage total instead of path-dependent.
        ChatSessionManager.getInstance(project).addListener(this)
    }

    /**
     * The project's Git conversation if it has one, with this service's listeners on it.
     *
     * The reconciliation is the point: asking the manager every time is what keeps this service from becoming
     * the stale second opinion the class KDoc forbids.
     */
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

    /**
     * The Git conversation, made and started if the project does not have one — **the one creation site**.
     *
     * Synchronised for the same reason [ChatSessionManager.gitChatOrCreate] is, and the two are not redundant:
     * that one guarantees a single session, this one guarantees a single `start()`. A second caller getting
     * past the check would re-apply the settings to a session that is already coming up and spawn its process
     * again.
     *
     * **The two locks cannot deadlock, and it is worth saying why rather than leaving it to be re-derived.**
     * This one is taken first and then the manager's, always in that order, and never the reverse: the fan-out
     * the manager does while holding its own lock lands in [onSessionsChanged], which takes no lock at all and
     * waits on no other thread — a `View` push is a JavaScript evaluation handed to the browser, not a
     * round trip. Re-entering this class from inside its own call is the same thread on a reentrant monitor.
     */
    @Synchronized
    fun sessionOrCreate(): ClaudeSession {
        current()?.let { return it }
        val chat = ChatSessionManager.getInstance(project).gitChatOrCreate()
        // Re-read rather than take `chat` on trust: `current` is the only place that moves the listeners, and
        // routing the fresh session through it is what keeps `attached` and the manager from ever disagreeing.
        current()
        ClaudeSettings.getInstance(project).applyTo(chat)
        chat.start()
        broadcast()
        return chat
    }

    /** Sends [text] as a turn, starting the conversation if this is the first thing ever asked of it. */
    fun send(text: String) {
        if (text.isBlank()) return
        sessionOrCreate().send(text)
    }

    fun interrupt() {
        current()?.interrupt()
    }

    /**
     * This conversation's pending cards, for the page's ONE permission region — empty until it has any.
     *
     * They are not drawn inside the Git view. The page has one place where a request card appears and one
     * renderer for it, and a second set inside a panel is a second place to look for the thing that is
     * blocking you. What makes a card answerable by the right session is the tag, not the location.
     */
    fun permissionGroup(): List<JcefCardPayload.Group> {
        val pending = current()?.cards?.pending().orEmpty()
        return if (pending.isEmpty()) emptyList() else listOf(JcefCardPayload.Group(pending, JcefBridge.SCOPE_GIT))
    }

    /**
     * Registers a page and paints the conversation into it **at once**.
     *
     * That immediate draw is the fix: a page used to be sent its first payload only once its own panel had
     * acted on the conversation, so a chat opened after the talking was done — or simply a different chat —
     * showed an empty pane over a conversation that was right there. Attaching now costs one payload build and
     * one `exec`, and the view drops it on the floor if it already has that exact string.
     */
    fun attach(view: View) {
        views.addIfAbsent(view)
        view.drawGitChat(payload())
    }

    /**
     * Unregisters a page. **The conversation is untouched**: it belongs to the project, and every other open
     * chat's Git view is a window onto the same one, so a closing panel that ended it would take theirs with it.
     */
    fun detach(view: View) {
        views.remove(view)
    }

    /**
     * The conversation as every page draws it: its rows, whether it is busy, and what it is waiting for.
     *
     * `starting` is the only reason this carries a lifecycle at all. With no tab there is no boot screen, and
     * without one the first press of *Commit with Claude* would look like nothing happening for as long as the
     * binary takes to come up — so the view says "starting" in its own pane instead of the page saying it over
     * everything.
     *
     * Built ONCE per change and handed to every view, rather than once per view: this runs on every streamed
     * delta of a turn, and with several chats open the per-view build was the same JSON serialised N times.
     */
    private fun payload(): String? {
        val chat = current() ?: return null
        return buildString {
            append("{\"running\":").append(chat.isRunning())
            append(",\"starting\":").append(chat.isStarting())
            append(",\"turnActive\":").append(chat.turnActive)
            // The LIVE shape, not the reconstructed one: this transcript is being written as we watch, so it
            // carries real row ids and real tool states, and `batchJson` is the builder that keeps them.
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

    // ── the three listeners: anything that moves, redraws every page ─────────────────────────────────────

    override fun onAdded(entry: TranscriptEntry, index: Int) = broadcast()

    override fun onUpdated(entry: TranscriptEntry) = broadcast()

    override fun onCleared() = broadcast()

    override fun onStateChanged() = broadcast()

    /**
     * A session was registered or removed. [broadcast] re-reads through [current], so this both picks up a Git
     * chat created by the other door and blanks every page when the conversation goes away.
     *
     * It fires for ordinary chats too, and that costs one payload build with nothing downstream: the string is
     * identical, and a view drops a repaint it has already made.
     */
    override fun onSessionsChanged() = broadcast()

    /**
     * The cards go to the page's ONE permission region, which each panel owns — so this asks every page to
     * re-push rather than pushing anything itself. Every page, not the one that started the turn: a card
     * raised while the user was in chat A has to be answerable from chat B, which is the whole of "global".
     */
    override fun onPermissionsChanged() {
        views.forEach { it.refreshGitChatPermissions() }
    }

    companion object {
        fun getInstance(project: Project): GitChatConversation = project.service()
    }
}
