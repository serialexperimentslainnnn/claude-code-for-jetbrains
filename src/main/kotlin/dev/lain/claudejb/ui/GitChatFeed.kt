package dev.lain.claudejb.ui

import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.ui.jcef.JcefCardPayload

/**
 * One page's window onto the project's Git conversation — **the placement, and nothing else**.
 *
 * **The Git chat is not a tab.** It was one, and being one is what put it in the row with the user's own
 * conversations, made its startup paint the full-window "Loading Claude Code" screen over whatever chat they
 * were in, and left the Git view able to do no more than send them somewhere else. It is a conversation ABOUT
 * the repository, so it belongs in the view of the repository.
 *
 * What that costs: a [JcefChatPanel] is bound to exactly one [ClaudeSession], and this is a SECOND one whose
 * transcript, turn state and permission cards have to reach the same browser without touching the first.
 * Everything here is therefore pushed under its own `cc.gitChat` namespace, and nothing in it may write to
 * the panel's own `cc.state`, `cc.batch` or `cc.permissions`.
 *
 * **What is NOT here, deliberately: the conversation.** The session, its listeners and the payload live in
 * [GitChatConversation], one per project. This class used to own all three, one instance per open chat, and
 * that is precisely how the same conversation came to look like a different one in every tab: a page only
 * ever subscribed as a side effect of *acting* on the chat, so a page that merely *looked* at the Git view
 * had no listener, had never been sent a payload, and drew an empty pane over a conversation that already
 * existed. What is left here is what is genuinely per-panel — which browser to paint into, and the last
 * string painted into it so an unchanged repaint costs nothing.
 */
internal class GitChatFeed(
    private val panel: JcefChatPanel,
    private val exec: (String) -> Unit,
) : GitChatConversation.View {

    private val conversation = GitChatConversation.getInstance(panel.project)

    /** The last payload pushed into THIS browser, so an unchanged repaint is a no-op. Per page, by nature. */
    private var lastPushed: String? = null

    init {
        // Registering is also what paints: the conversation hands over its current whole state on the spot,
        // so a chat opened long after the talking was done comes up showing it. See [GitChatConversation.attach].
        //
        // Which means [drawGitChat] runs re-entrantly, from inside this constructor — so both fields it touches
        // are declared ABOVE this block, and must stay there. Kotlin runs initializers in declaration order,
        // and a field moved below would be null for that first paint with nothing at the call site to say so.
        conversation.attach(this)
    }

    /** The Git conversation, created and started on first use. Delegates: a project has exactly one. */
    fun session(): ClaudeSession = conversation.sessionOrCreate()

    /** Sends [text] as a turn in the Git conversation, starting it if this is the first thing asked of it. */
    fun send(text: String) = conversation.send(text)

    fun interrupt() = conversation.interrupt()

    /** This conversation's pending cards, tagged, for the page's ONE permission region. */
    fun permissionGroup(): List<JcefCardPayload.Group> = conversation.permissionGroup()

    /**
     * Puts this conversation on screen **in this page** — what a prompted Git action does after prompting.
     *
     * Pressing *Commit with Claude* used to produce nothing visible at all: the turn ran in a tab the user was
     * not looking at, and the only sign was that tab badging itself eventually. The conversation is one of the
     * Git view's two destinations now, so going to it IS the feedback — the prompt is on screen as a row the
     * moment it is sent, and the card that gates the command appears under it.
     *
     * This one stayed per-panel while everything else moved to the project, and that asymmetry is the point:
     * the conversation is global, but *navigating to it* is a thing that happens to the page the user pressed
     * the button in. Switching every open chat's dashboard would move views nobody was looking at.
     *
     * The destination is chosen BEFORE the view is opened, and the order is the point: `showGitView` renders,
     * so setting the sub-view afterwards would draw the repository first and swap it a frame later.
     */
    fun show() {
        exec("window.CC && CC.dash && CC.dash.setGitSubView && CC.dash.setGitSubView('chat')")
        exec("window.cc.showGitView && window.cc.showGitView()")
    }

    /**
     * Paints the conversation into this browser, skipping a push that would change nothing.
     *
     * The null case is a real state and gets its own push, not silence: a page built before the project had a
     * Git conversation has to be told there is not one, or the pane would keep whatever it drew last.
     */
    override fun drawGitChat(payload: String?) {
        val json = payload ?: "null"
        if (json == lastPushed) return
        lastPushed = json
        exec("window.cc.gitChat && window.cc.gitChat($json)")
    }

    override fun refreshGitChatPermissions() = panel.pushPermissions()

    /**
     * Detaches this page, and nothing else.
     *
     * The conversation belongs to the project — every open chat's Git view is a window onto the same one — so
     * a closing panel that ended it would kill it for the tabs still open on it.
     */
    fun dispose() = conversation.detach(this)
}
