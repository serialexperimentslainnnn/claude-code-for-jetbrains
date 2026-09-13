package dev.lain.claudejb.headless

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.controller.commands.git.GitIntegration

class GitIntegrationRefreshHeadlessTest : BasePlatformTestCase() {

    fun `test a refresh asked while another is collecting is answered too`() {
        val integration = GitIntegration.getInstance(project)
        var first = 0
        var second = 0

        integration.refresh { first++ }
        integration.refresh { second++ }

        PlatformTestUtil.waitWithEventsDispatching(
            "both chats repaint: the first from its own collection, the second from the one queued behind it",
            { first == 1 && second == 1 },
            10,
        )
    }

    fun `test a caller is told once per refresh it asked for`() {
        val integration = GitIntegration.getInstance(project)
        var told = 0

        integration.refresh { told++ }
        PlatformTestUtil.waitWithEventsDispatching("the refresh completes", { told == 1 }, 10)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals(1, told)
    }
}
