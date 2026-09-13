package dev.lain.claudejb.mcp

import dev.lain.claudejb.model.mcp.toon.ToonFixtures
import dev.lain.claudejb.model.mcp.toon.ToonFixtures.cases
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import dev.lain.claudejb.model.mcp.toon.Toon as KotlinToon

class JavaToonFixturesTest {

    @TestFactory
    fun `encode fixtures`(): List<DynamicTest> = cases("encode").map { (file, case) ->
        dynamicTest("$file: ${case.name}") {
            assertEquals(case.expected.jsonPrimitive.content, Toon.encode(Json.parse(case.input.toString()), case.options.toJava()))
        }
    }

    @TestFactory
    fun `decode fixtures`(): List<DynamicTest> = cases("decode").map { (file, case) ->
        dynamicTest("$file: ${case.name}") {
            val text = case.input.jsonPrimitive.content
            if (case.shouldError) {
                assertThrows<ToonException> { Toon.decode(text, case.options.toJava()) }
            } else {
                val expected = Json.write(normalize(Json.parse(case.expected.toString())))
                assertEquals(expected, Json.write(normalize(Toon.decode(text, case.options.toJava()))))
            }
        }
    }

    @TestFactory
    fun `the two codecs agree byte for byte on every encode fixture`(): List<DynamicTest> = cases("encode").map { (file, case) ->
        dynamicTest("$file: ${case.name}") {
            val kotlin = KotlinToon.encode(case.input, case.options.toOptions())
            assertEquals(kotlin, Toon.encode(Json.parse(case.input.toString()), case.options.toJava()))
        }
    }

    @TestFactory
    fun `what one codec writes the other reads`(): List<DynamicTest> =
        cases("decode").filterNot { (_, case) -> case.shouldError }.map { (file, case) ->
            dynamicTest("$file: ${case.name}") {
                val expected = normalize(Json.parse(case.expected.toString()))
                assertEquals(expected, normalize(Toon.decode(KotlinToon.encode(case.expected))))
                assertEquals(expected, normalize(Json.parse(KotlinToon.decode(Toon.encode(Json.parse(case.expected.toString()))).toString())))
            }
        }

    @TestFactory
    fun `the JSON model round-trips every fixture document`(): List<DynamicTest> =
        (cases("encode") + cases("decode")).map { (file, case) ->
            dynamicTest("$file: ${case.name}") {
                val text = case.input.toString()
                assertEquals(normalize(Json.parse(text)), normalize(Json.parse(Json.write(Json.parse(text)))))
            }
        }

    private fun normalize(value: Any?): Any? = when (value) {
        is Map<*, *> -> LinkedHashMap(value.mapValues { normalize(it.value) })
        is List<*> -> value.map(::normalize)
        is BigDecimal -> value.stripTrailingZeros()
        else -> value
    }

    private fun ToonFixtures.Options.toJava(): Toon.Options = Toon.Options(indentSize, delimiter.single(), strict)
}
