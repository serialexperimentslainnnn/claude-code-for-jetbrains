package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.session.PluginAgentIndex
import dev.lain.claudejb.session.SessionHistory
import java.nio.file.Files
import java.nio.file.Path

class SessionHistoryHeadlessTest : BasePlatformTestCase() {

    private lateinit var tempHome: Path
    private var previousHome: String? = null

    private val history get() = SessionHistory.getInstance(project)

    override fun setUp() {
        super.setUp()
        previousHome = PluginAgentIndex.homeOverride
        tempHome = Files.createTempDirectory("claude-home-test")
        PluginAgentIndex.homeOverride = tempHome.toString()
        history.setOpenSessions(emptyList())
    }

    override fun tearDown() {
        try {
            PluginAgentIndex.homeOverride = previousHome
        } finally {
            super.tearDown()
        }
    }

    fun `test getInstance returns the project service`() {
        assertNotNull(history)
        assertSame(history, SessionHistory.getInstance(project))
    }

    fun `test setOpenSessions then openSessions preserves order`() {
        history.setOpenSessions(listOf("a", "b"))
        assertEquals(listOf("a", "b"), history.openSessions())
    }

    fun `test the list survives a fresh service reading the same file`() {
        history.setOpenSessions(listOf("x", "y", "z"))
        assertEquals(listOf("x", "y", "z"), SessionHistory.getInstance(project).openSessions())
        val file = tempHome.resolve("ide/claude-code-native/open-chats.json")
        assertTrue("the plugin must write its own file", Files.exists(file))
        assertTrue(Files.readString(file).contains("\"x\""))
    }

    fun `test blank ids are filtered out`() {
        history.setOpenSessions(listOf("a", "", "  ", "b"))
        assertEquals(listOf("a", "b"), history.openSessions())
    }

    fun `test a corrupt file reads as empty instead of throwing`() {
        val file = tempHome.resolve("ide/claude-code-native/open-chats.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, "{not json")
        assertEquals(emptyList<String>(), history.openSessions())
    }
}
