package dev.lain.claudejb.ui

import dev.lain.claudejb.session.AttentionReason
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.SessionListener
import dev.lain.claudejb.session.TranscriptEntry
import dev.lain.claudejb.session.TranscriptModel
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.ui.jcef.JcefBridge
import dev.lain.claudejb.ui.jcef.JcefCardPayload
import dev.lain.claudejb.ui.jcef.JcefTranscriptPayload

/**
 * The Git conversation, pushed INTO the Git view of a page that belongs to a different session.
 *
 * **The Git chat is not a tab any more.** It was one, and being one is what put it in the row with the user's
 * own conversations, made its startup paint the full-window "Loading Claude Code" screen over whatever chat
 * you were in, and left the Git view able to do no more than send you somewhere else. It is a conversation
 * ABOUT the repository, so it belongs in the view of the repository.
 *
 * What that costs, and it is the whole of this class: a `JcefChatPanel` is bound to exactly one
 * [ClaudeSession], and this is a SECOND one whose transcript, turn state and permission cards have to reach
 * the same browser without touching the first. Everything here is therefore pushed under its own
 * `cc.gitChat` namespace, and nothing in it may write to the panel's own `cc.state`, `cc.batch` or
 * `cc.permissions`.
 *
 * **Its permission cards come with it, and that is not a detail.** Every turn in this chat runs with forced
 * approval ([ClaudeSession.gitIntegration]) — a `git commit` stops and shows the command before it runs — so
 * a view that could show the conversation but not the card would be a view you cannot finish anything from.
 *
 * **Lazily created, silently started.** The session is made the first time the Git view is actually looked
 * at, not when the tool window opens: it is a second `claude` process with its own context and cost, and
 * paying for it on every project open would be charging everybody for a feature most sessions never use.
 */
internal class GitChatFeed(
    private val panel: JcefChatPanel,
    private val exec: (String) -> Unit,
) : SessionListener, TranscriptModel.Listener {

    /** The Git conversation, once something has asked for it. EDT-confined. */
    private var session: ClaudeSession? = null

    /** The last payload pushed, so an unchanged repaint is a no-op — this fires on every delta. */
    private var lastPushed: String? = null

    /**
     * The Git session, created and started on first use.
     *
     * Reuses one the manager already has: a project has one Git conversation, and a second would be a second
     * process arguing with the first about the same working tree.
     */
    fun session(): ClaudeSession {
        session?.let { return it }
        val manager = ChatSessionManager.getInstance(panel.project)
        val fresh = manager.gitChat() == null
        val chat = manager.gitChatOrCreate()
        session = chat
        chat.transcript.addListener(this)
        chat.addListener(this)
        if (fresh) {
            ClaudeSettings.getInstance(panel.project).applyTo(chat)
            // Silently: no tab, so no boot screen anywhere. Whether it is still starting is a field of THIS
            // view's payload, drawn inside the view — see `push`.
            chat.start()
        }
        push()
        return chat
    }

    /** Sends [text] as a turn in the Git conversation, starting it if this is the first thing asked of it. */
    fun send(text: String) {
        if (text.isBlank()) return
        session().send(text)
    }

    /**
     * Puts this conversation on screen — what a prompted Git action does after prompting.
     *
     * Pressing *Commit with Claude* used to produce nothing visible at all: the turn ran in a tab the user
     * was not looking at, and the only sign was that tab badging itself eventually. The conversation is one
     * of the Git view's two destinations now, so going to it IS the feedback — the prompt is on screen as a
     * row the moment it is sent, and the card that gates the command appears under it.
     *
     * The destination is chosen BEFORE the view is opened, and the order is the point: `showGitView` renders,
     * so setting the sub-view afterwards would draw the repository first and swap it a frame later.
     */
    fun show() {
        exec("window.CC && CC.dash && CC.dash.setGitSubView && CC.dash.setGitSubView('chat')")
        exec("window.cc.showGitView && window.cc.showGitView()")
    }

    fun interrupt() {
        session?.interrupt()
    }

    /**
     * This conversation's pending cards, for the page's ONE permission region — empty until it has any.
     *
     * They are not drawn inside the Git view. The page has one place where a request card appears and one
     * renderer for it, and a second set inside a panel is a second place to look for the thing that is
     * blocking you. What makes them answerable is the tag, not the location.
     */
    fun permissionGroup(): List<JcefCardPayload.Group> {
        val pending = session?.pendingPermissions().orEmpty()
        return if (pending.isEmpty()) emptyList() else listOf(JcefCardPayload.Group(pending, JcefBridge.SCOPE_GIT))
    }

    fun resolvePermission(id: String, allow: Boolean) {
        session?.resolvePermission(id, allow)
    }

    /**
     * Pushes the conversation as the view draws it: its rows, whether it is busy, and whatever it is waiting
     * for an answer to.
     *
     * `starting` is the only reason this carries a lifecycle at all. With no tab there is no boot screen, and
     * without one the first press of *Commit with Claude* would look like nothing happening for as long as the
     * binary takes to come up — so the view says "starting" in its own chat pane instead of the page saying it
     * over everything.
     *
     * Skipped when nothing changed: this is called from a transcript listener, i.e. once per streamed delta.
     */
    fun push() {
        val chat = session
        if (chat == null) {
            exec("window.cc.gitChat && window.cc.gitChat(null)")
            return
        }
        val payload = buildString {
            append("{\"running\":").append(chat.isRunning())
            append(",\"starting\":").append(chat.isStarting())
            append(",\"turnActive\":").append(chat.turnActive)
            // The LIVE shape, not the reconstructed one: this transcript is being written as we watch, so it
            // carries real row ids and real tool states, and `batchJson` is the builder that keeps them.
            append(",\"rows\":").append(JcefTranscriptPayload.batchJson(rows(chat)))
            append("}")
        }
        if (payload == lastPushed) return
        lastPushed = payload
        exec("window.cc.gitChat && window.cc.gitChat($payload)")
    }

    private fun rows(chat: ClaudeSession): List<Pair<TranscriptEntry, Int>> =
        chat.transcript.entries.mapIndexed { index, entry -> entry to index }

    // ── the two listeners: anything that moves, redraws the pane ─────────────────────────────────────────

    override fun onAdded(entry: TranscriptEntry, index: Int) = push()

    override fun onUpdated(entry: TranscriptEntry) = push()

    override fun onCleared() = push()

    override fun onStateChanged() = push()

    // The cards go to the page's ONE permission region, which the panel owns — so this asks the panel to
    // re-push rather than pushing anything itself.
    override fun onPermissionsChanged() = panel.pushPermissions()

    /**
     * Nothing. A background chat asking for attention normally badges its tab and raises a notification, and
     * this one has no tab — the card is already on screen in the view that started the action, which is where
     * the user pressed the button.
     */
    override fun onAttention(reason: AttentionReason) = Unit

    fun dispose() {
        session?.transcript?.removeListener(this)
        session?.removeListener(this)
        session = null
    }
}
