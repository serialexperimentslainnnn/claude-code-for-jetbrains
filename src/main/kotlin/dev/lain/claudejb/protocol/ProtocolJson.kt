package dev.lain.claudejb.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Single tolerant [Json] instance for the whole stream-json protocol.
 *
 * The Claude Code control protocol is broad (dozens of message and control subtypes, many of which
 * this plugin ignores) and evolves between binary versions, so decoding is deliberately lenient:
 * unknown keys/types must never crash the reader loop.
 *
 * Incoming messages are decoded with the typed models below; outgoing messages are built explicitly
 * as [kotlinx.serialization.json.JsonObject]s in [ControlProtocol] to keep their wire shape exact.
 */
val ClaudeJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

/** Null-safe string accessor for a [JsonObject] field that is a JSON primitive. */
internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

/** Null-safe int accessor for a [JsonObject] field that is a JSON primitive. */
internal fun JsonObject.intField(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull
