package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.session.ClaudeSession

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
 * Nothing here calls [ClaudeSession.start]: the process is real and this is about routing, not running.
 */
class GitChatRoutingHeadlessTest : BasePlatformTestCase() {

    private val manager get() = ChatSessionManager.getInstance(project)

    override fun tearDown() {
        try {
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
}
