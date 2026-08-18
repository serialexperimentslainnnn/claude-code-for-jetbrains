package dev.lain.claudejb.headless

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.protocol.ClaudeEvent
import dev.lain.claudejb.protocol.ElicitationRequest
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.Speaker
import dev.lain.claudejb.ui.GitChatConversation
import dev.lain.claudejb.ui.jcef.JcefBridge

/**
 * Headless: where a Git action's turn goes.
 *
 * **The bug this exists for.** A button on the Git view resolved its target chat as "the existing Git chat, or
 * else the session showing the view", and the view is drawn in ANY chat's dashboard. With no Git chat open —
 * the common case, since nothing opened one on its own — *Commit with Claude* wrote its entire turn into the
 * conversation the user was having: the context cost, the money and the transcript to read past that a
 * separate conversation exists to avoid. The same action taken with one already open landed correctly, which
 * is what made it look like two different features.
 *
 * **What changed under the rule, and why the test moved with it.** The Git conversation used to be a TAB, and
 * these assertions were about [dev.lain.claudejb.ui.TabSessionCommands] opening one and whether it selected
 * it. It has no tab now — it is embedded in the Git view, so a prompted action's turn appears where the
 * button was pressed instead of in a tab the user was not looking at — and the find-or-create moved to the
 * one place that can answer it without a tool window at all. The RULE is untouched: one Git conversation per
 * project, never the user's own, and asking for it must not move what the rest of the IDE calls the active
 * chat.
 *
 * **The second half of the same rule is presentation, and it is where the rule was actually broken.** One
 * conversation per project was true and stayed true; what was not global was the SUBSCRIPTION to it. Every
 * open chat built its own feed, and a feed attached to the session only as a side effect of *acting* on it —
 * so a page that merely *looked* at the Git view had no listener, had never been handed a payload, and drew
 * an empty pane. It reads as a brand-new conversation, and going back to the chat that did act showed the
 * real one still whole, because there had only ever been one session. The assertions below are therefore
 * about [GitChatConversation]: what every page is given, and when.
 *
 * They stop at that service rather than driving a [dev.lain.claudejb.ui.JcefChatPanel], deliberately: a panel
 * is a live JCEF browser, which does not exist headless, and the seam the defect lived on is exactly the one
 * between the conversation and the page. A fake [GitChatConversation.View] is the page.
 *
 * Nothing here calls [ClaudeSession.start]: the process is real and this is about routing, not running. The
 * Git chat is therefore seeded through [ChatSessionManager.gitChatOrCreate], which registers without starting.
 */
class GitChatRoutingHeadlessTest : BasePlatformTestCase() {

    private val manager get() = ChatSessionManager.getInstance(project)
    private val conversation get() = GitChatConversation.getInstance(project)

    /** A page. Records what it was told to paint, and how often it was told to rebuild the card region. */
    private class RecordingView : GitChatConversation.View {
        val payloads = mutableListOf<String?>()
        var permissionRefreshes = 0

        override fun drawGitChat(payload: String?) {
            payloads += payload
        }

        override fun refreshGitChatPermissions() {
            permissionRefreshes++
        }
    }

    /** Attached views, so the shared light-fixture project does not carry one test's pages into the next. */
    private val attached = mutableListOf<RecordingView>()

    private fun page(): RecordingView = RecordingView().also {
        attached += it
        conversation.attach(it)
    }

    private fun flush() = PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

    override fun tearDown() {
        try {
            // Only when this test actually attached one: the light fixture shares its project across the
            // class, so asking for the service here would instantiate it for a test that never wanted it.
            if (attached.isNotEmpty()) attached.forEach { conversation.detach(it) }
            manager.all().forEach { runCatching { manager.remove(it) } }
        } finally {
            super.tearDown()
        }
    }

    fun `test the git chat is created when there is not one yet`() {
        val ordinary = manager.create()

        val git = manager.gitChatOrCreate()

        assertNotSame("a Git action must never run in the user's own chat", ordinary, git)
        assertTrue(git.gitIntegration)
        assertSame(git, manager.gitChat())
    }

    fun `test asking twice reuses the one git chat`() {
        val first = manager.gitChatOrCreate()

        val second = manager.gitChatOrCreate()

        assertSame(first, second)
        assertEquals("a second Git conversation is a second process arguing about one working tree", 1, manager.all().count { it.gitIntegration })
    }

    fun `test creating it leaves the active chat alone`() {
        // Creating a session makes it the manager's active one, and a tab selection is what used to settle
        // that a moment later. There is no tab now, so nothing would — and "the active chat", which is what
        // every dialog outside the tool window asks for, would point at a conversation nobody is looking at.
        val ordinary = manager.create()

        manager.gitChatOrCreate()

        assertSame(ordinary, manager.active)
    }

    fun `test finding an existing one also leaves the active chat alone`() {
        manager.gitChatOrCreate()
        val ordinary = manager.create()

        manager.gitChatOrCreate()

        assertSame(ordinary, manager.active)
    }

    // ── the conversation is the project's; a page is only where it is painted ────────────────────────────

    fun `test a page that attaches late is given the whole conversation, not what happens next`() {
        // The reported symptom, at its seam: talk in one chat, open the Git view in another, see an empty
        // pane. The late page must be handed everything that was already said, because there is no catch-up
        // channel behind it — the host pushes the whole conversation or the page has nothing.
        val chat = manager.gitChatOrCreate()
        val early = page()
        chat.transcript.add(Speaker.USER, "commit what is staged")

        val late = page()

        assertEquals(
            "a page that only LOOKS must see exactly what the page that acted sees",
            early.payloads.last(),
            late.payloads.last(),
        )
        assertTrue(
            "the whole transcript, not the tail: ${late.payloads.last()}",
            late.payloads.last().orEmpty().contains("commit what is staged"),
        )
    }

    fun `test a page attached before there is a conversation is told so, and painted once there is`() {
        val early = page()

        assertEquals("no Git chat yet is a state, not silence", listOf<String?>(null), early.payloads)

        manager.gitChatOrCreate().transcript.add(Speaker.USER, "initialise this repository")

        assertTrue(early.payloads.last().orEmpty().contains("initialise this repository"))
    }

    fun `test every attached page is repainted on every change`() {
        val chat = manager.gitChatOrCreate()
        val a = page()
        val b = page()

        chat.transcript.add(Speaker.USER, "squash those two")

        assertTrue("the page whose user is elsewhere is repainted too", a.payloads.last().orEmpty().contains("squash those two"))
        assertEquals(a.payloads.last(), b.payloads.last())
    }

    fun `test closing a page stops painting it and leaves the conversation to the others`() {
        val chat = manager.gitChatOrCreate()
        val stays = page()
        val closed = page()
        val paintedBeforeClosing = closed.payloads.size

        conversation.detach(closed)
        chat.transcript.add(Speaker.USER, "not that file")

        assertEquals("a closed page must stop being painted", paintedBeforeClosing, closed.payloads.size)
        assertTrue(stays.payloads.last().orEmpty().contains("not that file"))
        // The symmetric error, and the worse one: a panel disposing must not take the project's conversation
        // with it. Every other chat's Git view is a window onto this same session.
        assertSame(chat, manager.gitChat())
    }

    fun `test a pending card reaches every page, whichever chat the user is looking at`() {
        // Every Git turn runs with forced approval, so a `git commit` stops and waits. If the card only
        // reached the page that started the turn, switching chat with one up would leave the user holding a
        // conversation they cannot finish from where they are standing.
        val chat = manager.gitChatOrCreate()
        val here = page()
        val elsewhere = page()

        chat.handleEventForTest(
            ClaudeEvent.Elicitation("r1", ElicitationRequest(mcpServerName = "git", message = "Authorize?")),
        )
        flush()

        assertTrue("the page the user pressed the button in", here.permissionRefreshes >= 1)
        assertTrue("and the one they walked over to", elsewhere.permissionRefreshes >= 1)
        val group = conversation.permissionGroup().single()
        assertEquals("answering it must reach the Git session, not the panel's own", JcefBridge.SCOPE_GIT, group.scope)
        assertEquals("r1", group.cards.single().requestId)
    }

    fun `test two pages asking for the conversation get the one session`() {
        // Seeded through the manager so nothing here spawns a `claude` process: what is under test is that the
        // second ask REUSES — a second process would argue with the first about the same working tree, and
        // being tabless it would be invisible while it did.
        val seeded = manager.gitChatOrCreate()

        val fromOnePage = conversation.sessionOrCreate()
        val fromAnother = conversation.sessionOrCreate()

        assertSame(seeded, fromOnePage)
        assertSame(fromOnePage, fromAnother)
        assertEquals(1, manager.all().count { it.gitIntegration })
    }
}
