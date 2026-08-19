package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranscriptReconcilerTest {

    private val cap = TranscriptModel.MAX_ENTRIES

    private fun TranscriptModel.fill(count: Int) = repeat(count) { add(Speaker.USER, "x") }

    @Test
    fun `assistant deltas grow one entry in place`() {
        val model = TranscriptModel()
        val reconciler = TranscriptReconciler(model)

        reconciler.appendAssistant("Hel")
        reconciler.appendAssistant("lo")

        assertEquals(1, model.entries.size)
        assertEquals(Speaker.ASSISTANT, model.entries.single().speaker)
        assertEquals("Hello", model.entries.single().text)
    }

    @Test
    fun `an assistant delta after its entry was trimmed starts a new entry and leaves the dead one alone`() {
        val model = TranscriptModel()
        val reconciler = TranscriptReconciler(model)
        reconciler.appendAssistant("old")
        val dropped = model.entries.single()
        model.fill(cap)
        assertTrue(dropped.trimmed)

        reconciler.appendAssistant("new")

        val tail = model.entries.last()
        assertFalse(tail.trimmed)
        assertEquals("new", tail.text)
        assertEquals(Speaker.ASSISTANT, tail.speaker)
        assertEquals("old", dropped.text)
    }

    @Test
    fun `a thinking delta after its entry was trimmed starts a new entry and leaves the dead one alone`() {
        val model = TranscriptModel()
        val reconciler = TranscriptReconciler(model)
        reconciler.appendThinking("old thought")
        val dropped = model.entries.single()
        model.fill(cap)
        assertTrue(dropped.trimmed)

        reconciler.appendThinking("new thought")

        val tail = model.entries.last()
        assertEquals("new thought", tail.text)
        assertEquals(Speaker.THINKING, tail.speaker)
        assertEquals("old thought", dropped.text)
    }

    @Test
    fun `finalizeAssistant adds a fresh entry when the streamed one was trimmed`() {
        val model = TranscriptModel()
        val reconciler = TranscriptReconciler(model)
        reconciler.appendAssistant("partial")
        val dropped = model.entries.single()
        model.fill(cap)

        reconciler.finalizeAssistant("the whole answer")

        val tail = model.entries.last()
        assertEquals("the whole answer", tail.text)
        assertEquals(Speaker.ASSISTANT, tail.speaker)
        assertEquals("partial", dropped.text)
    }

    @Test
    fun `finalizeThinking adds a fresh entry when the streamed one was trimmed`() {
        val model = TranscriptModel()
        val reconciler = TranscriptReconciler(model)
        reconciler.appendThinking("partial reasoning")
        val dropped = model.entries.single()
        model.fill(cap)

        reconciler.finalizeThinking("the whole reasoning")

        val tail = model.entries.last()
        assertEquals("the whole reasoning", tail.text)
        assertEquals(Speaker.THINKING, tail.speaker)
        assertEquals("partial reasoning", dropped.text)
    }

    @Test
    fun `finalizeThinking adds a fresh entry when the settled thinking row was trimmed`() {
        val model = TranscriptModel()
        val reconciler = TranscriptReconciler(model)
        reconciler.appendThinking("reasoning")
        val droppedThinking = model.entries.first()
        reconciler.appendAssistant("answer")
        model.fill(cap)
        assertTrue(droppedThinking.trimmed)

        reconciler.finalizeThinking("full reasoning")

        assertEquals("full reasoning", model.entries.last().text)
        assertEquals(Speaker.THINKING, model.entries.last().speaker)
        assertEquals("reasoning", droppedThinking.text)
    }

    @Test
    fun `finalizeThinking replaces the settled row in place while it is alive`() {
        val model = TranscriptModel()
        val reconciler = TranscriptReconciler(model)
        reconciler.appendThinking("reasoning")
        val thinking = model.entries.first()
        reconciler.appendAssistant("answer")

        reconciler.finalizeThinking("full reasoning")

        assertEquals(2, model.entries.size)
        assertSame(thinking, model.entries.first())
        assertEquals("full reasoning", thinking.text)
        assertEquals("answer", model.entries.last().text)
    }

    @Test
    fun `a blank finalizeThinking neither blanks the streamed fold nor opens an empty one`() {
        val model = TranscriptModel()
        val reconciler = TranscriptReconciler(model)
        reconciler.appendThinking("what the user was shown")

        reconciler.finalizeThinking("")

        assertEquals(1, model.entries.size)
        assertEquals("what the user was shown", model.entries.single().text)

        reconciler.finalizeThinking("")
        assertEquals(1, model.entries.size)
    }

    @Test
    fun `a thinking block labelled with a parent tool use id never reaches this transcript`() {
        val model = TranscriptModel()
        val reconciler = TranscriptReconciler(model)

        reconciler.appendThinking("the agent's reasoning", parentToolUseId = "toolu_agent_1")
        reconciler.finalizeThinking("the agent's whole reasoning", parentToolUseId = "toolu_agent_1")

        assertTrue(model.entries.isEmpty())
    }

    @Test
    fun `an unlabelled thinking block still reaches this transcript`() {
        val model = TranscriptModel()
        val reconciler = TranscriptReconciler(model)

        reconciler.appendThinking("my own reasoning", parentToolUseId = null)
        reconciler.finalizeThinking("my own whole reasoning", parentToolUseId = null)

        assertEquals(1, model.entries.size)
        assertEquals(Speaker.THINKING, model.entries.single().speaker)
        assertEquals("my own whole reasoning", model.entries.single().text)
    }

    @Test
    fun `a subagent's text is dropped too, and does not cut the main run's thinking block`() {
        val model = TranscriptModel()
        val reconciler = TranscriptReconciler(model)

        reconciler.appendThinking("first half ")
        reconciler.appendAssistant("the agent talking", parentToolUseId = "toolu_agent_1")
        reconciler.appendThinking("second half")

        assertEquals(1, model.entries.size)
        assertEquals(Speaker.THINKING, model.entries.single().speaker)
        assertEquals("first half second half", model.entries.single().text)
    }

    @Test
    fun `a subagent's finalized thinking cannot clear the pointer the main run's own finalize needs`() {
        val model = TranscriptModel()
        val reconciler = TranscriptReconciler(model)
        reconciler.appendThinking("streamed reasoning")
        val streamed = model.entries.single()

        reconciler.finalizeThinking("the agent's reasoning", parentToolUseId = "toolu_agent_1")
        reconciler.finalizeThinking("my full reasoning")

        assertEquals(1, model.entries.size)
        assertSame(streamed, model.entries.single())
        assertEquals("my full reasoning", model.entries.single().text)
    }

    @Test
    fun `a streamed thinking block replaced by its finalized form is exactly one row`() {
        val model = TranscriptModel()
        val reconciler = TranscriptReconciler(model)

        reconciler.appendThinking("let me ")
        reconciler.appendThinking("think about it")
        reconciler.finalizeThinking("let me think about it, summarized")

        assertEquals(1, model.entries.size)
        assertEquals(Speaker.THINKING, model.entries.single().speaker)
        assertEquals("let me think about it, summarized", model.entries.single().text)
    }

    @Test
    fun `belongsHere is the one rule, stated once`() {
        assertTrue(TranscriptReconciler.belongsHere(null))
        assertFalse(TranscriptReconciler.belongsHere("toolu_agent_1"))
    }

    @Test
    fun `onMessageBoundary drops all three pointers so the next delta starts fresh`() {
        val model = TranscriptModel()
        val reconciler = TranscriptReconciler(model)
        reconciler.appendThinking("thought")
        reconciler.appendAssistant("answer")

        reconciler.onMessageBoundary()
        reconciler.appendAssistant("next message")
        reconciler.finalizeThinking("late reasoning")

        assertEquals(4, model.entries.size)
        assertEquals("thought", model.entries[0].text)
        assertEquals("answer", model.entries[1].text)
        assertEquals("next message", model.entries[2].text)
        assertEquals("late reasoning", model.entries[3].text)
    }
}
