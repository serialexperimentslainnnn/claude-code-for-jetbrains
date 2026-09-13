package dev.lain.claudejb.model.mcp.toon

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import java.math.BigDecimal
import kotlin.math.abs

internal object ToonText {

    fun key(name: String): String {
        requireScalars(name)
        return if (UNQUOTED_KEY.matches(name)) name else quote(name)
    }

    fun primitive(value: JsonPrimitive, delimiter: Char): String = when {
        value is JsonNull -> "null"
        value.isString -> string(value.content, delimiter)
        value.content == "true" || value.content == "false" -> value.content
        else -> value.content.toBigDecimalOrNull()?.let(::canonical) ?: "null"
    }

    fun string(value: String, delimiter: Char): String {
        requireScalars(value)
        return if (needsQuotes(value, delimiter)) quote(value) else value
    }

    fun canonical(number: BigDecimal): String {
        val n = number.stripTrailingZeros()
        if (n.signum() == 0) return "0"
        val magnitude = n.abs()
        if (magnitude >= PLAIN_MIN && magnitude < PLAIN_MAX) return n.toPlainString()
        val digits = n.unscaledValue().abs().toString()
        val exponent = digits.length - 1 - n.scale()
        val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
        val sign = if (n.signum() < 0) "-" else ""
        val exponentSign = if (exponent < 0) "-" else "+"
        return "$sign${mantissa}e$exponentSign${abs(exponent)}"
    }

    fun value(token: String): JsonElement = when {
        token.startsWith('"') -> JsonPrimitive(unquote(token))
        token == "true" -> JsonPrimitive(true)
        token == "false" -> JsonPrimitive(false)
        token == "null" -> JsonNull
        NUMBER.matches(token) && !LEADING_ZERO.containsMatchIn(token) -> number(token)
        else -> JsonPrimitive(token)
    }

    fun keyOf(token: String): String = if (token.startsWith('"')) unquote(token) else token

    fun unquote(token: String): String {
        val out = StringBuilder()
        var i = 1
        while (i < token.length) {
            val c = token[i]
            if (c == '"') {
                if (i != token.lastIndex) toonError("characters after the closing quote in $token")
                return out.toString()
            }
            i = if (c == '\\') escape(token, i + 1, out) else i.also { out.append(c) }
            i++
        }
        toonError("unterminated string: $token")
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun number(token: String): JsonPrimitive = JsonUnquotedLiteral(canonical(BigDecimal(token)))

    private fun escape(token: String, at: Int, out: StringBuilder): Int {
        val c = token.getOrNull(at) ?: toonError("unterminated escape in $token")
        ESCAPES[c]?.let {
            out.append(it)
            return at
        }
        if (c != 'u') toonError("invalid escape in $token")
        val end = at + 1 + HEX_DIGITS
        val hex = token.substring(at + 1, minOf(end, token.length))
        if (hex.length != HEX_DIGITS || !hex.all(::isHex)) toonError("truncated unicode escape in $token")
        val code = hex.toInt(16)
        if (code in SURROGATE_MIN..SURROGATE_MAX) toonError("surrogate escape in $token")
        out.append(code.toChar())
        return end - 1
    }

    private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun quote(value: String): String {
        val out = StringBuilder("\"")
        for (c in value) {
            when {
                c == '\\' -> out.append("\\\\")
                c == '"' -> out.append("\\\"")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(HEX_DIGITS, '0'))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }

    private fun needsQuotes(s: String, delimiter: Char): Boolean =
        s.isEmpty() || s.first() in LEADING || s.last() in TRAILING || ambiguous(s, delimiter)

    private fun ambiguous(s: String, delimiter: Char): Boolean =
        s in LITERALS || NUMERIC_LIKE.matches(s) || s.any { it in STRUCTURAL || it < ' ' || it == delimiter }

    private fun requireScalars(value: String) {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val paired = c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()
            if (paired) {
                i++
            } else if (c.isSurrogate()) {
                toonError("unpaired surrogate at index $i")
            }
            i++
        }
    }

    private const val HEX_DIGITS = 4
    private const val LEADING = " \t-#"
    private const val TRAILING = " \t"
    private const val STRUCTURAL = ":\"\\[]{}"
    private val LITERALS = setOf("true", "false", "null")
    private const val SURROGATE_MIN = 0xD800
    private const val SURROGATE_MAX = 0xDFFF
    private val ESCAPES = mapOf('\\' to '\\', '"' to '"', 'n' to '\n', 'r' to '\r', 't' to '\t')
    private val PLAIN_MIN = BigDecimal("1e-6")
    private val PLAIN_MAX = BigDecimal("1e21")
    private val UNQUOTED_KEY = Regex("[A-Za-z_][A-Za-z0-9_.]*")
    private val NUMERIC_LIKE = Regex("[+-]?[0-9]+(?:[.][0-9]+)?(?:[eE][+-]?[0-9]+)?")
    private val NUMBER = Regex("-?[0-9]+(?:[.][0-9]+)?(?:[eE][+-]?[0-9]+)?")
    private val LEADING_ZERO = Regex("^-?0[0-9]")
}
