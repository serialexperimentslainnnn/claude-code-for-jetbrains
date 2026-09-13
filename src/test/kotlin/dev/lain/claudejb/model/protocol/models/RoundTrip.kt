package dev.lain.claudejb.model.protocol.models

import dev.lain.claudejb.model.protocol.ClaudeJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals

internal object RoundTrip {

    private val sparse = Json(from = ClaudeJson) { encodeDefaults = false }

    fun <T> check(serializer: KSerializer<T>, full: T, empty: T) {
        listOf(ClaudeJson, sparse).forEach { json ->
            assertEquals(full, json.decodeFromString(serializer, json.encodeToString(serializer, full)))
            assertEquals(empty, json.decodeFromString(serializer, json.encodeToString(serializer, empty)))
        }
    }
}
