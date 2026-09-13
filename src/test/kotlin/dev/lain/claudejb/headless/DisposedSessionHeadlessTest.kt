package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.controller.session.ClaudeSession

class DisposedSessionHeadlessTest : BasePlatformTestCase() {

    fun `test a disposed session refuses to start, so a sign-in that finishes late spawns nothing`() {
        val session = ClaudeSession(project, "Chat")
        session.dispose()

        assertTrue(session.lifecycle.disposed)
        assertFalse(session.start(resume = false))
        assertFalse(session.isRunning())
        assertFalse(session.lifecycle.isStarting())
    }
}
