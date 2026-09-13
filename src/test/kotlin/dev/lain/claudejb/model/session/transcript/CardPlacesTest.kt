package dev.lain.claudejb.model.session.transcript

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CardPlacesTest {

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun one(tool: String, args: String = "{}", result: String? = "{}"): String? =
        CardPlaces.of(tool, json(args), result?.let(::json)).singleOrNull()?.let { it.label + " " + it.href }

    @Test
    fun `every tool that has a place in the IDE gets one link, and the rest get none`() {
        assertEquals("View commit jb://commit?hash=abc123", one("git_commit", result = """{"hash":"abc123"}"""))
        assertEquals(null, one("git_commit", result = """{"hash":""}"""))
        assertEquals("View log jb://log", one("git_log"))
        assertEquals("View log jb://log", one("git_remote"))
        assertEquals("Open Commit view jb://toolwindow?id=Commit", one("git_status"))
        assertEquals("Open Commit view jb://toolwindow?id=Commit", one("git_stage"))
        assertEquals("Open diff in IDE jb://toolwindow?id=Commit", one("git_diff"))
        assertEquals("Open diff in IDE jb://diff?file=src%2FA+B.kt", one("git_diff", args = """{"path":"src/A B.kt"}"""))
        assertEquals("Branches jb://action?id=Git.Branches", one("git_branches"))
        assertEquals("View in Terminal jb://terminal?tab=Claude", one("shell"))
        assertEquals("View run jb://run?name=Kotlin+tests", one("run_configuration", args = """{"name":"Kotlin tests"}"""))
        assertEquals("View run jb://toolwindow?id=Run", one("run_tests", args = """{"path":"src/T.kt"}"""))
        assertEquals("View build jb://build", one("build"))
        assertEquals("Open Problems jb://problems", one("problems"))
        assertEquals(null, one("read_file", args = """{"path":"a.kt"}"""))
        assertEquals(null, one("git_commit", result = null))
    }
}
