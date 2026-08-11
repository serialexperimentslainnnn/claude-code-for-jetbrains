package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.session.PluginAgentIndex
import dev.lain.claudejb.session.SessionHistory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Headless: the open-chat list round-trips through the plugin's own file under `~/.claude`.
 *
 * It used to be a `PersistentStateComponent` in `workspace.xml`, and the reason it is not any more is the
 * failure this suite cannot reproduce but the user hit: the platform decides when that file reaches disk, so
 * reinstalling the plugin and restarting straight afterwards restored an older list and dropped the last
 * chat opened. What this test CAN pin is the contract that replaced it.
 *
 * **The home is redirected to a temp directory for the whole class.** A test JVM writing into the
 * developer's real `~/.claude` is not hypothetical here: it is exactly how an earlier run harvested and
 * deleted live credentials, which is why `CredentialsVault` grew the same override.
 */
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
        // The persistence IS the file, so "reload" means reading it again rather than replaying a state
        // object the platform hands back.
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
