package dev.lain.claudejb.protocol

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pure tests for [parseElicitationFields]: only flat primitive properties become fields; anything else is empty. */
class ElicitationFieldsTest {

    private fun schema(json: String): JsonObject = ClaudeJson.parseToJsonElement(json) as JsonObject

    @Test
    fun `extracts string, number, integer and boolean fields with title and required`() {
        val s = schema(
            """
            {"properties":{
               "name":{"type":"string","title":"Your name"},
               "age":{"type":"integer"},
               "score":{"type":"number"},
               "agree":{"type":"boolean"}
             },"required":["name","agree"]}
            """.trimIndent(),
        )
        val fields = parseElicitationFields(s)
        assertEquals(4, fields.size)
        val name = fields.first { it.name == "name" }
        assertEquals("string", name.type)
        assertEquals("Your name", name.title)
        assertTrue(name.required)
        assertTrue(fields.first { it.name == "agree" }.required)
        assertTrue(!fields.first { it.name == "age" }.required)
    }

    @Test
    fun `a nested or object property yields an empty list`() {
        val s = schema("""{"properties":{"ok":{"type":"string"},"nested":{"type":"object"}}}""")
        assertTrue(parseElicitationFields(s).isEmpty())
    }

    @Test
    fun `absent properties yields an empty list`() {
        assertTrue(parseElicitationFields(schema("""{"type":"object"}""")).isEmpty())
    }

    @Test
    fun `null schema yields an empty list`() {
        assertTrue(parseElicitationFields(null).isEmpty())
    }
}
