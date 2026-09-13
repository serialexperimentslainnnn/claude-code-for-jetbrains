package dev.lain.claudejb.model.mcp.toon

import dev.lain.claudejb.model.mcp.toon.ToonFixtures.cases
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

class ToonFixturesTest {

    @Test
    fun `the official suite is present and large`() {
        assertTrue(cases("encode").size >= ToonFixtures.MIN_ENCODE) { "only ${cases("encode").size} encode fixtures" }
        assertTrue(cases("decode").size >= ToonFixtures.MIN_DECODE) { "only ${cases("decode").size} decode fixtures" }
    }

    @TestFactory
    fun `encode fixtures`(): List<DynamicTest> = cases("encode").map { (file, case) ->
        dynamicTest("$file: ${case.name}") {
            assertEquals(case.expected.jsonPrimitive.content, Toon.encode(case.input, case.options.toOptions()))
        }
    }

    @TestFactory
    fun `decode fixtures`(): List<DynamicTest> = cases("decode").map { (file, case) ->
        dynamicTest("$file: ${case.name}") {
            val text = case.input.jsonPrimitive.content
            if (case.shouldError) {
                assertThrows<ToonException> { Toon.decode(text, case.options.toOptions()) }
            } else {
                assertEquals(normalize(case.expected).toString(), normalize(Toon.decode(text, case.options.toOptions())).toString())
            }
        }
    }

    @TestFactory
    fun `every encode fixture survives the round trip`(): List<DynamicTest> = cases("encode").map { (file, case) ->
        dynamicTest("$file: ${case.name}") {
            val options = case.options.toOptions()
            assertEquals(normalize(case.input), normalize(Toon.decode(Toon.encode(case.input, options), options)))
        }
    }

    @TestFactory
    fun `every decoded fixture value survives the round trip`(): List<DynamicTest> =
        cases("decode").filterNot { (_, case) -> case.shouldError }.map { (file, case) ->
            dynamicTest("$file: ${case.name}") {
                assertEquals(normalize(case.expected), normalize(Toon.decode(Toon.encode(case.expected))))
            }
        }

    private fun normalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { normalize(it.value) })
        is JsonArray -> JsonArray(element.map(::normalize))
        is JsonPrimitive -> normalizeNumber(element)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun normalizeNumber(element: JsonPrimitive): JsonPrimitive {
        if (element is JsonNull || element.isString) return element
        val number = element.content.toBigDecimalOrNull() ?: return element
        return JsonUnquotedLiteral(number.stripTrailingZeros().toPlainString())
    }
}
