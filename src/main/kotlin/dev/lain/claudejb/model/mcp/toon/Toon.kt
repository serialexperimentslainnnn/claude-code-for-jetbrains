package dev.lain.claudejb.model.mcp.toon

import kotlinx.serialization.json.JsonElement

enum class ToonDelimiter(val symbol: Char) {
    COMMA(','),
    TAB('\t'),
    PIPE('|'),
}

data class ToonOptions(
    val indentSize: Int = 2,
    val delimiter: ToonDelimiter = ToonDelimiter.COMMA,
    val strict: Boolean = true,
)

class ToonException(message: String) : RuntimeException(message)

internal fun toonError(message: String): Nothing = throw ToonException(message)

object Toon {

    fun encode(value: JsonElement, options: ToonOptions = ToonOptions()): String = ToonEncoder(options).encode(value)

    fun decode(text: String, options: ToonOptions = ToonOptions()): JsonElement = ToonDecoder(text, options).decode()
}
