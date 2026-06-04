package dev.lain.claudejb.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises [ProtocolParser.parse] for the AskUserQuestion path: a `control_request` whose subtype is
 * `can_use_tool` and whose [tool_name] is `AskUserQuestion`. The questions arrive in the tool input and
 * are extracted by [parseAskQuestions] from the resulting [ClaudeEvent.PermissionRequest]. The label,
 * description and preview fields of every option are load-bearing for the UI's wrapped option cards.
 */
class ProtocolParserAskUserQuestionTest {

    private fun parseSingle(line: String): ClaudeEvent = ProtocolParser.parse(line).single()

    private fun askLine(
        question: String,
        header: String,
        options: List<Triple<String, String, String?>>,
        multiSelect: Boolean,
        toolUseId: String = "tu_ask_1",
        requestId: String = "req_1",
    ): String {
        val opts = options.joinToString(",") { (label, desc, preview) ->
            val previewField = preview?.let { ""","preview":${quote(it)}""" } ?: ""
            """{"label":${quote(label)},"description":${quote(desc)}$previewField}"""
        }
        return """
            {"type":"control_request","request_id":${quote(requestId)},
             "request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion",
                        "tool_use_id":${quote(toolUseId)},
                        "input":{"questions":[
                          {"question":${quote(question)},"header":${quote(header)},
                           "multiSelect":$multiSelect,
                           "options":[$opts]}
                        ]}}}
        """.trimIndent().replace("\n", "")
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Test
    fun `single-select question with all option fields is fully parsed`() {
        val line = askLine(
            question = "Which framework?",
            header = "Project setup",
            options = listOf(
                Triple("Kotlin", "Idiomatic JVM", "fun main() {}"),
                Triple("Java", "Most familiar", "public static void main"),
            ),
            multiSelect = false,
        )
        val event = parseSingle(line) as ClaudeEvent.PermissionRequest

        assertEquals("req_1", event.requestId)
        assertEquals("AskUserQuestion", event.request.toolName)
        assertEquals("tu_ask_1", event.request.toolUseId)

        val questions = parseAskQuestions(event.request.input)
        assertEquals(1, questions.size)
        val q = questions.single()
        assertEquals("Which framework?", q.question)
        assertEquals("Project setup", q.header)
        assertFalse(q.multiSelect)
        assertEquals(2, q.options.size)
        assertEquals("Kotlin", q.options[0].label)
        assertEquals("Idiomatic JVM", q.options[0].description)
        assertEquals("fun main() {}", q.options[0].preview)
        assertEquals("Java", q.options[1].label)
        assertEquals("public static void main", q.options[1].preview)
    }

    @Test
    fun `multiSelect true is preserved through the round trip`() {
        val line = askLine(
            question = "Pick targets",
            header = "Build",
            options = listOf(Triple("JVM", "", null), Triple("JS", "", null)),
            multiSelect = true,
        )
        val event = parseSingle(line) as ClaudeEvent.PermissionRequest
        val q = parseAskQuestions(event.request.input).single()
        assertTrue(q.multiSelect)
    }

    @Test
    fun `option without preview decodes to null preview`() {
        val line = askLine(
            question = "?",
            header = "h",
            options = listOf(Triple("A", "desc", null)),
            multiSelect = false,
        )
        val event = parseSingle(line) as ClaudeEvent.PermissionRequest
        val opt = parseAskQuestions(event.request.input).single().options.single()
        assertEquals("A", opt.label)
        assertEquals("desc", opt.description)
        assertNull(opt.preview, "absent preview must decode to null, not empty string")
    }

    @Test
    fun `long description survives untruncated`() {
        val longDesc = "x".repeat(5_000)
        val line = askLine(
            question = "Q?",
            header = "H",
            options = listOf(Triple("A", longDesc, null)),
            multiSelect = false,
        )
        val event = parseSingle(line) as ClaudeEvent.PermissionRequest
        val opt = parseAskQuestions(event.request.input).single().options.single()
        assertEquals(5_000, opt.description.length)
        assertEquals(longDesc, opt.description)
    }

    @Test
    fun `malformed questions array degrades to empty list, not a crash`() {
        // The questions key is present but each entry has a non-decodable shape (number instead of object).
        val line = """{"type":"control_request","request_id":"r","request":{"subtype":"can_use_tool",
            "tool_name":"AskUserQuestion","tool_use_id":"tu","input":{"questions":[1,2,3]}}}""".replace("\n", "")
        val event = parseSingle(line) as ClaudeEvent.PermissionRequest
        // parseAskQuestions wraps decode in runCatching and returns emptyList on any failure.
        assertTrue(parseAskQuestions(event.request.input).isEmpty())
    }

    @Test
    fun `tool_use_id is preserved on the wrapped CanUseToolRequest`() {
        val line = askLine(
            question = "?",
            header = "h",
            options = listOf(Triple("A", "", null)),
            multiSelect = false,
            toolUseId = "tu_xyz_42",
        )
        val event = parseSingle(line) as ClaudeEvent.PermissionRequest
        assertEquals("tu_xyz_42", event.request.toolUseId)
    }

    @Test
    fun `title field on the request is exposed (nullable, may be absent)`() {
        // Some binary versions attach a title on the control_request. When absent, it decodes to null.
        val line = askLine(
            question = "?", header = "h",
            options = listOf(Triple("A", "", null)),
            multiSelect = false,
        )
        val event = parseSingle(line) as ClaudeEvent.PermissionRequest
        assertNull(event.request.title, "title is optional and absent in this fixture")
    }

    @Test
    fun `event PermissionRequest carries the unmodified questions input for later AskUserQuestion rendering`() {
        val line = askLine(
            question = "Pick one", header = "h",
            options = listOf(Triple("A", "da", "pa"), Triple("B", "db", "pb")),
            multiSelect = false,
        )
        val event = parseSingle(line) as ClaudeEvent.PermissionRequest
        // The raw input must still contain the original `questions` JSON array so the broker can
        // pass it to parseAskQuestions() — this is exactly the path PermissionBroker.handle relies on.
        val questionsNode = event.request.input["questions"]
        assertNotNull(questionsNode)
        val parsed = parseAskQuestions(event.request.input)
        assertEquals(2, parsed.single().options.size)
    }
}
