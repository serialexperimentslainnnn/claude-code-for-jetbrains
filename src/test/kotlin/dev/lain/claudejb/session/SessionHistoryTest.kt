package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests [SessionHistory]'s pure open-session id (de)serialization without an Application/Project:
 * ordered round-trip and tolerance of blank/garbage input (→ empty, never throws).
 */
class SessionHistoryTest {

    @Test
    fun `open-session ids round-trip in order`() {
        val ids = listOf("s3", "s1", "s2")
        assertEquals(ids, SessionHistory.decodeIds(SessionHistory.encodeIds(ids)))
    }

    @Test
    fun `decodeIds of blank or garbage is empty, never throws`() {
        assertTrue(SessionHistory.decodeIds("").isEmpty())
        assertTrue(SessionHistory.decodeIds("   ").isEmpty())
        assertTrue(SessionHistory.decodeIds("{not json").isEmpty())
        assertTrue(SessionHistory.decodeIds("42").isEmpty())
    }
}
