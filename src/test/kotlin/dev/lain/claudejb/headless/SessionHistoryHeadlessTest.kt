package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.session.PluginAgentIndex
import dev.lain.claudejb.session.SessionHistory
import dev.lain.claudejb.session.SessionStore
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.SecretStore
import java.nio.file.Files
import java.nio.file.Path

class SessionHistoryHeadlessTest : BasePlatformTestCase() {

    private lateinit var tempHome: Path
    private var previousHome: String? = null

    private val history get() = SessionHistory.getInstance(project)

    private val scope get() = ClaudeSettings.getInstance(project).scope

    override fun setUp() {
        super.setUp()
        SecretStore.storeOverride = mutableMapOf()
        previousHome = PluginAgentIndex.homeOverride
        tempHome = Files.createTempDirectory("claude-home-test")
        PluginAgentIndex.homeOverride = tempHome.toString()
        history.setOpenSessions(emptyList())
    }

    override fun tearDown() {
        try {
            PluginAgentIndex.homeOverride = previousHome
            SecretStore.storeOverride = null
        } finally {
            super.tearDown()
        }
    }

    private fun sharedFile(): Path = tempHome.resolve("ide/claude-code-native/open-chats.json")

    private fun writeSharedFile(body: String) {
        Files.createDirectories(sharedFile().parent)
        Files.writeString(sharedFile(), body)
    }

    fun `test getInstance returns the project service`() {
        assertNotNull(history)
        assertSame(history, SessionHistory.getInstance(project))
    }

    fun `test setOpenSessions then openSessions preserves order`() {
        history.setOpenSessions(listOf("a", "b"))
        assertEquals(listOf("a", "b"), history.openSessions())
    }

    fun `test the list survives a fresh service reading the same scope`() {
        history.setOpenSessions(listOf("x", "y", "z"))

        assertEquals(listOf("x", "y", "z"), SessionHistory.getInstance(project).openSessions())
        assertEquals(
            "it belongs in the safe, under this project's own entry",
            SessionHistory.encodeIds(listOf("x", "y", "z")),
            SecretStore.get(scope.openChatsName),
        )
    }

    fun `test blank ids are filtered out`() {
        history.setOpenSessions(listOf("a", "", "  ", "b"))
        assertEquals(listOf("a", "b"), history.openSessions())
    }

    fun `test a corrupt entry reads as empty instead of throwing`() {
        SecretStore.set(scope.openChatsName, "{not json")
        assertEquals(emptyList<String>(), history.openSessions())
    }

    fun `test what the old shared file held for this project comes across, and the file goes`() {
        SecretStore.clear(scope.openChatsName)
        val mine = SessionStore.encodePath(project.basePath!!)
        writeSharedFile("""{"$mine":["kept-one","kept-two"]}""")

        assertEquals(listOf("kept-one", "kept-two"), history.openSessions())
        assertFalse(
            "nothing of ours was left in it, so it must not survive as a staler second copy",
            Files.exists(sharedFile()),
        )
    }

    fun `test another project's slice of the shared file is left for the IDE that owns it`() {
        SecretStore.clear(scope.openChatsName)
        writeSharedFile("""{"-somewhere-else-entirely":["theirs"]}""")

        assertEquals(emptyList<String>(), history.openSessions())
        assertTrue(
            "an entry this IDE has never opened belongs to another installation",
            Files.readString(sharedFile()).contains("theirs"),
        )
    }
}
