package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.session.Speaker
import dev.lain.claudejb.session.TranscriptModel
import dev.lain.claudejb.session.TranscriptReconciler

/**
 * Headless: [TranscriptReconciler] folds streaming deltas / finalized blocks / boundaries into a
 * [TranscriptModel] with the same semantics the session used inline. No platform service is needed, but it
 * runs as a headless test (EDT contract) for consistency with the other session-layer tests.
 */
class TranscriptReconcilerTest : BasePlatformTestCase() {

    private lateinit var transcript: TranscriptModel
    private lateinit var reconciler: TranscriptReconciler

    override fun setUp() {
        super.setUp()
        transcript = TranscriptModel()
        reconciler = TranscriptReconciler(transcript)
    }

    private fun entries() = transcript.entries

    fun `test assistant deltas accumulate into one entry`() {
        reconciler.appendAssistant("Hel")
        reconciler.appendAssistant("lo, ")
        reconciler.appendAssistant("world")

        assertEquals(1, entries().size)
        val e = entries()[0]
        assertEquals(Speaker.ASSISTANT, e.speaker)
        assertEquals("Hello, world", e.text)
    }

    fun `test finalize assistant replaces live text`() {
        reconciler.appendAssistant("partial draft")
        reconciler.finalizeAssistant("final complete text")

        assertEquals(1, entries().size)
        assertEquals("final complete text", entries()[0].text)
    }

    fun `test finalize assistant with no live entry adds a fresh one`() {
        reconciler.finalizeAssistant("standalone")

        assertEquals(1, entries().size)
        assertEquals(Speaker.ASSISTANT, entries()[0].speaker)
        assertEquals("standalone", entries()[0].text)
    }

    fun `test thinking deltas accumulate into one entry`() {
        reconciler.appendThinking("let me ")
        reconciler.appendThinking("think")

        assertEquals(1, entries().size)
        val e = entries()[0]
        assertEquals(Speaker.THINKING, e.speaker)
        assertEquals("let me think", e.text)
    }

    fun `test finalize thinking replaces live text`() {
        reconciler.appendThinking("draft reasoning")
        reconciler.finalizeThinking("summarized reasoning")

        assertEquals(1, entries().size)
        assertEquals(Speaker.THINKING, entries()[0].speaker)
        assertEquals("summarized reasoning", entries()[0].text)
    }

    fun `test assistant delta ends a live thinking block`() {
        reconciler.appendThinking("reasoning")
        // First assistant delta must not grow the thinking entry; it starts a new assistant entry.
        reconciler.appendAssistant("answer ")
        reconciler.appendAssistant("text")

        assertEquals(2, entries().size)
        assertEquals(Speaker.THINKING, entries()[0].speaker)
        assertEquals("reasoning", entries()[0].text)
        assertEquals(Speaker.ASSISTANT, entries()[1].speaker)
        assertEquals("answer text", entries()[1].text)
    }

    fun `test message boundary resets live entries`() {
        reconciler.appendAssistant("first message")
        reconciler.onMessageBoundary()
        reconciler.appendAssistant("second message")

        assertEquals(2, entries().size)
        assertEquals("first message", entries()[0].text)
        assertEquals("second message", entries()[1].text)
    }

    fun `test boundary resets both assistant and thinking`() {
        reconciler.appendThinking("t1")
        reconciler.appendAssistant("a1")
        reconciler.onMessageBoundary()
        reconciler.appendThinking("t2")
        reconciler.appendAssistant("a2")

        assertEquals(4, entries().size)
        assertEquals("t1", entries()[0].text)
        assertEquals("a1", entries()[1].text)
        assertEquals("t2", entries()[2].text)
        assertEquals("a2", entries()[3].text)
    }

    fun `test finalize closes the block so next delta starts fresh`() {
        reconciler.appendAssistant("one")
        reconciler.finalizeAssistant("one final")
        // No boundary needed: finalize already cleared the live pointer.
        reconciler.appendAssistant("two")

        assertEquals(2, entries().size)
        assertEquals("one final", entries()[0].text)
        assertEquals("two", entries()[1].text)
    }

    fun `test addSubagentText anchors under parent without breaking live stream`() {
        reconciler.appendAssistant("top-level streaming ")
        reconciler.addSubagentText("subagent reply", parentToolUseId = "agent-1")
        // The live top-level entry must still be growable after the subagent insert.
        reconciler.appendAssistant("continues")

        val top = entries().first { it.parentToolUseId == null && it.speaker == Speaker.ASSISTANT }
        val sub = entries().first { it.parentToolUseId == "agent-1" }
        assertEquals("top-level streaming continues", top.text)
        assertEquals(Speaker.ASSISTANT, sub.speaker)
        assertEquals("subagent reply", sub.text)
    }
}
