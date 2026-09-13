package dev.lain.claudejb.model.mcp.toon

import dev.lain.claudejb.MainSources
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.io.File

internal object ToonFixtures {

    const val MIN_ENCODE = 150
    const val MIN_DECODE = 250

    fun cases(category: String): List<Pair<String, Case>> =
        MainSources.root("src/test/resources/toon/$category").listFiles { file: File -> file.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }
            .flatMap { file -> JSON.decodeFromString<Fixture>(file.readText()).tests.map { file.nameWithoutExtension to it } }

    @Serializable
    class Fixture(val tests: List<Case>)

    @Serializable
    class Case(
        val name: String,
        val input: JsonElement,
        val expected: JsonElement = JsonNull,
        val options: Options = Options(),
        val shouldError: Boolean = false,
    )

    @Serializable
    class Options(val strict: Boolean = true, val delimiter: String = ",", val indentSize: Int = 2) {
        fun toOptions(): ToonOptions = ToonOptions(
            indentSize = indentSize,
            delimiter = ToonDelimiter.entries.first { it.symbol.toString() == delimiter },
            strict = strict,
        )
    }

    private val JSON = Json { ignoreUnknownKeys = true }
}
