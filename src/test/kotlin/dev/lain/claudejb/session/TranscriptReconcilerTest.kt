package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Streaming reconciliation against a real [TranscriptModel] — both are IDE-free, so this needs no fixture.
 *
 * The behaviour under test beyond ordinary streaming: the model is bounded, so an entry a live pointer still
 * points at can be dropped from under it. Appending to a dropped entry writes text nowhere the page will ever
 * see, so a trimmed pointer must count as no live block and the next delta must start a fresh entry. Trimming
 * is driven the only honest way — through the model, by pushing past its cap.
 */
class TranscriptReconcilerTest {

    private val cap = TranscriptModel.MAX_ENTRIES

    /** Pushes [count] plain rows through the model, which is what makes older entries fall off the head. */
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
        assertEquals("old", dropped.text) // the delta went to the new row, not into a row nobody can see
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
        // A text delta closes the growing thinking block but keeps the settled pointer for the finalize-replace.
        // Once that row is gone too, the finalized block must land as a new row instead of vanishing into it.
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

        // Replaced where it already was — never appended as a second, out-of-order "Thought process".
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

        reconciler.finalizeThinking("") // nothing live at all: still no empty fold
        assertEquals(1, model.entries.size)
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

        // Four rows: the two from the first message, the new assistant paragraph, and a finalized thinking block
        // that had no settled pointer left to replace.
        assertEquals(4, model.entries.size)
        assertEquals("thought", model.entries[0].text)
        assertEquals("answer", model.entries[1].text)
        assertEquals("next message", model.entries[2].text)
        assertEquals("late reasoning", model.entries[3].text)
    }
}
