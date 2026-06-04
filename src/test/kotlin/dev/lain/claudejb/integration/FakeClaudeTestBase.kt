package dev.lain.claudejb.integration

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.settings.ClaudeSettings

/**
 * Base for the fake-claude integration tests: spins a real [ClaudeSession] whose `claude` binary is the
 * deterministic `bin/fake-claude` stand-in (path injected via the `claudejb.fakeClaude` system property by
 * the Gradle `test` task). A JSONL fixture from `src/test/resources/fixtures/` is selected per test by
 * setting `ClaudeSettings.state.envVars = "FAKE_FIXTURE=<abs path>"` — the plugin forwards env vars
 * verbatim to the subprocess ([ClaudeSettings.resolveEnv] → [ClaudeProcess]).
 *
 * Events arrive asynchronously on a background reader thread and are reconciled onto the EDT via
 * invokeLater, so assertions poll under [waitUntil], pumping the IDE event queue between checks.
 *
 * HARNESS CONSTRAINT — EOF stdin: under BasePlatformTestCase the spawned process's stdin is at EOF, so the
 * fake cannot consume the host's writes (initialize / user prompt / control_response). Fixtures therefore
 * replay autonomously (eager emit paced with `_sleep_ms`) instead of gating on `_wait_stdin`; the host's
 * writes (a prompt via [ClaudeSession.send], an allow via [ClaudeSession.resolvePermission]) still drive the
 * plugin-side state machine, they are simply dropped at the (dead) binary stdin. Tests assert on the
 * resulting observable state, never on the binary having read anything back.
 */
abstract class FakeClaudeTestBase : BasePlatformTestCase() {

    protected val manager get() = ChatSessionManager.getInstance(project)

    /** Absolute path to the fake binary, provided by the Gradle test task. */
    private val fakeClaude: String
        get() = System.getProperty("claudejb.fakeClaude")
            ?: error("System property claudejb.fakeClaude not set (configured in build.gradle.kts test task)")

    /** Absolute path to a fixture under src/test/resources/fixtures/. */
    protected fun fixture(name: String): String {
        val url = javaClass.classLoader.getResource("fixtures/$name")
            ?: error("Fixture not found on the test classpath: fixtures/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    /**
     * Points the project's settings at the fake binary and the given fixture, then creates a session.
     * Does NOT start it — call [ClaudeSession.start] (or [ClaudeSession.send], which cold-starts) yourself.
     */
    protected fun newSessionWith(fixtureName: String): ClaudeSession {
        val settings = ClaudeSettings.getInstance(project)
        settings.state.claudePath = fakeClaude
        // The plugin forwards these KEY=VALUE lines to the subprocess env (resolveEnv → parseEnv).
        settings.state.envVars = "FAKE_FIXTURE=${fixture(fixtureName)}"
        settings.state.sourceScript = ""
        // The session spawns the process in project.basePath; BasePlatformTestCase's temp basePath may not
        // exist on disk yet (it's a notional path), so materialize it or the spawn fails with
        // WorkingDirectoryNotFoundException.
        project.basePath?.let { java.io.File(it).mkdirs() }
        return manager.create()
    }

    /** Pumps the IDE event queue until [condition] is true or [timeoutMs] elapses. */
    protected fun waitUntil(message: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ApplicationManager.getApplication().invokeAndWait {
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            }
            if (condition()) return
            Thread.sleep(50)
        }
        // One last pump + check so a just-arrived event isn't missed by the loop boundary.
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
