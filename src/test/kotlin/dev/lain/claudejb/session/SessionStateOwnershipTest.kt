package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SessionStateOwnershipTest {

    private val owned = listOf(
        "model", "effort", "permissionMode", "thinkingTokens",
        "allowedTools", "disallowedTools", "settingSources", "includePartialMessages",
        "ideMcpEnabled", "ideMcpTransport", "ideMcpPort", "customMcpServers",
        "maxTurns", "maxBudgetUsd", "fallbackModel", "addDirs", "betas", "strictMcpConfig",
        "cachedEnv",
    )

    private val writers = setOf("ClaudeSession.kt", "SessionLiveSettings.kt")

    private fun productionSources(): List<File> {
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "the source root moved: this gate has to move with it")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `only the session and its live settings write a session's options`() {
        val assignment = Regex("""\bsession\.(${owned.joinToString("|")})\s*=(?!=)""")
        val offenders = productionSources()
            .filter { it.name !in writers }
            .flatMap { file ->
                assignment.findAll(file.readText()).map { "${file.name}: ${it.value.trim()}" }
            }

        assertEquals(
            emptyList<String>(),
            offenders,
            "a second writer of a live session's options appeared — see this test's doc before adding one",
        )
    }

    @Test
    fun `the guarded list still matches what the session declares as internally settable`() {
        val source = File("src/main/kotlin/dev/lain/claudejb/session/ClaudeSession.kt").readText()
        val declared = Regex("""var (\w+)[^\n]*\n\s*internal set""").findAll(source)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(emptySet<String>(), declared - owned.toSet() - setOf("sessionId"))
    }
}
