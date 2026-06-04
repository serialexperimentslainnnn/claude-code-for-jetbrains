package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.MemoryRecallInfo
import dev.lain.claudejb.protocol.RecalledMemory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pure tests for the memory_recall header summary + markdown body. */
class MemoryRecallFormatterTest {

    @Test
    fun `summary pluralizes and names the mode`() {
        val one = MemoryRecallInfo(mode = "select", memories = listOf(RecalledMemory(path = "a")))
        assertEquals("Recalled 1 memory (select)", MemoryRecallFormatter.summary(one))
        val many = MemoryRecallInfo(mode = "synthesize", memories = List(3) { RecalledMemory(path = "p$it") })
        assertEquals("Recalled 3 memories (synthesize)", MemoryRecallFormatter.summary(many))
    }

    @Test
    fun `summary omits an empty mode`() {
        val info = MemoryRecallInfo(mode = "", memories = listOf(RecalledMemory(path = "a"), RecalledMemory(path = "b")))
        assertEquals("Recalled 2 memories", MemoryRecallFormatter.summary(info))
    }

    @Test
    fun `body has one bullet per memory with scope, path and snippet`() {
        val info = MemoryRecallInfo(
            mode = "select",
            memories = listOf(RecalledMemory(path = "notes.md", scope = "team", content = "hello world")),
        )
        val body = MemoryRecallFormatter.body(info)
        assertEquals(1, body.lines().size)
        assertTrue(body.startsWith("- **team** notes.md"))
        assertTrue(body.contains("hello world"))
    }

    @Test
    fun `body truncates a long snippet`() {
        val long = "x".repeat(500)
        val info = MemoryRecallInfo(memories = listOf(RecalledMemory(path = "p", content = long)))
        assertTrue(MemoryRecallFormatter.body(info).endsWith("…"))
    }
}
