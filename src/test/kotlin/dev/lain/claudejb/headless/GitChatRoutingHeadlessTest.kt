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

class GitChatRoutingHeadlessTest : BasePlatformTestCase() {

    private val manager get() = ChatSessionManager.getInstance(project)
    private val conversation get() = GitChatConversation.getInstance(project)

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

    private val attached = mutableListOf<RecordingView>()

    private fun page(): RecordingView = RecordingView().also {
        attached += it
        conversation.attach(it)
    }

    private fun flush() = PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

    override fun tearDown() {
        try {
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

    fun `test a page that attaches late is given the whole conversation, not what happens next`() {
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
        assertSame(chat, manager.gitChat())
    }

    fun `test a pending card reaches every page, whichever chat the user is looking at`() {
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
        val seeded = manager.gitChatOrCreate()

        val fromOnePage = conversation.sessionOrCreate()
        val fromAnother = conversation.sessionOrCreate()

        assertSame(seeded, fromOnePage)
        assertSame(fromOnePage, fromAnother)
        assertEquals(1, manager.all().count { it.gitIntegration })
    }
}
