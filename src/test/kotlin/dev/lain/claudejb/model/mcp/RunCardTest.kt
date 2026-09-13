package dev.lain.claudejb.model.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RunCardTest {

    private fun input(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `run names the inner tool and shows its arguments as TOON`() {
        val card = RunCard.of("mcp__code__run", input("""{"tool":"read_file","args":{"path":"src/App.kt","limit":40}}"""))!!
        assertEquals("Claude wants to use read_file on the code server", card.title)
        assertEquals("path: src/App.kt\nlimit: 40", card.summary)
    }

    @Test
    fun `the discovery meta-tools say which server is being asked`() {
        assertEquals("Claude asks the vcs server for its domains", RunCard.of("mcp__vcs__domains", input("{}"))!!.title)
        assertEquals("Claude asks the ops server for its build tools", RunCard.of("mcp__ops__tools", input("""{"domain":"build"}"""))!!.title)
    }

    @Test
    fun `other tools keep their own card`() {
        assertNull(RunCard.of("Bash", input("""{"command":"ls"}""")))
        assertNull(RunCard.of("mcp__jetbrains__get_file_text", input("{}")))
        assertNull(RunCard.of("mcp__code__read_file", input("{}")))
    }

    @Test
    fun `a run without a tool argument still gets a card`() {
        assertEquals("Claude wants to use ? on the code server", RunCard.of("mcp__code__run", input("{}"))!!.title)
    }
}
