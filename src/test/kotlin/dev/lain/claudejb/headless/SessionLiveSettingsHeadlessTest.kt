package dev.lain.claudejb.headless

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.controller.session.ClaudeSession
import dev.lain.claudejb.model.session.launch.LaunchOptions
import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.model.settings.SecretStore

class SessionLiveSettingsHeadlessTest : BasePlatformTestCase() {

    private lateinit var session: ClaudeSession

    private val settings get() = ClaudeSettings.getInstance(project)

    override fun setUp() {
        super.setUp()
        SecretStore.storeOverride = mutableMapOf()
        session = ClaudeSession(project, "Chat")
        Disposer.register(testRootDisposable, session)
    }

    override fun tearDown() {
        try {
            SecretStore.storeOverride = null
        } finally {
            super.tearDown()
        }
    }

    fun `test a model chosen from the composer is the one the next chat starts with`() {
        session.settings.changeModel("sonnet")
        assertEquals("sonnet", session.launch.model)
        assertEquals("sonnet", settings.state.model)
        assertEquals("sonnet", LaunchOptions.from(settings).model)
    }

    fun `test effort and thinking chosen from the composer are stored like the mode pill already was`() {
        session.settings.changeEffort("low")
        session.settings.changeThinkingTokens(null)
        assertEquals("low", settings.state.effort)
        assertEquals(0, settings.state.thinkingTokens)

        session.settings.changeThinkingTokens(1)
        assertEquals(1, settings.state.thinkingTokens)
    }

    fun `test adopting the stored settings changes the session and leaves the store as it was`() {
        settings.update { it.model = "haiku" }
        session.settings.changeModel("sonnet", persist = false)
        assertEquals("sonnet", session.launch.model)
        assertEquals("haiku", settings.state.model)
    }
}
