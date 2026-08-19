package dev.lain.claudejb.integration

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.settings.ClaudeSettings

abstract class FakeClaudeTestBase : BasePlatformTestCase() {

    protected val manager get() = ChatSessionManager.getInstance(project)

    private val fakeClaude: String
        get() = System.getProperty("claudejb.fakeClaude")
            ?: error("System property claudejb.fakeClaude not set (configured in build.gradle.kts test task)")

    protected fun fixture(name: String): String {
        val url = javaClass.classLoader.getResource("fixtures/$name")
            ?: error("Fixture not found on the test classpath: fixtures/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    protected fun newSessionWith(fixtureName: String): ClaudeSession {
        val settings = ClaudeSettings.getInstance(project)
        settings.state.claudePath = fakeClaude
        settings.state.envVars = "FAKE_FIXTURE=${fixture(fixtureName)}\nANTHROPIC_API_KEY=fake-claude-needs-none"
        settings.state.sourceScript = ""
        project.basePath?.let { java.io.File(it).mkdirs() }
        return manager.create()
    }

    protected fun waitUntil(message: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ApplicationManager.getApplication().invokeAndWait {
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            }
            if (condition()) return
            Thread.sleep(50)
        }
        ApplicationManager.getApplication().invokeAndWait {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
        assertTrue("Timed out after ${timeoutMs}ms waiting for: $message", condition())
    }

    override fun tearDown() {
        try {
            manager.all().forEach { runCatching { manager.remove(it) } }
        } finally {
            super.tearDown()
        }
    }
}
