package dev.lain.claudejb.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewPromptTest {

    @Test
    fun `never asks before the threshold`() {
        for (turns in 0 until ReviewPrompt.TURNS_BEFORE_ASK) {
            assertFalse(ReviewPrompt.shouldAsk(turns, asked = false), "must not ask at $turns turns")
        }
    }

    @Test
    fun `asks exactly at the threshold`() {
        assertTrue(ReviewPrompt.shouldAsk(ReviewPrompt.TURNS_BEFORE_ASK, asked = false))
    }

    @Test
    fun `never asks again once asked — at, below or far above the threshold`() {
        assertFalse(ReviewPrompt.shouldAsk(ReviewPrompt.TURNS_BEFORE_ASK, asked = true))
        assertFalse(ReviewPrompt.shouldAsk(ReviewPrompt.TURNS_BEFORE_ASK * 100, asked = true))
        assertFalse(ReviewPrompt.shouldAsk(0, asked = true))
    }

    @Test
    fun `the counter saturates instead of growing without bound`() {
        var turns = 0
        repeat(ReviewPrompt.TURNS_BEFORE_ASK * 3) { turns = ReviewPrompt.recordTurn(turns) }
        assertEquals(ReviewPrompt.TURNS_BEFORE_ASK, turns)
    }

    @Test
    fun `counting up from zero crosses the threshold exactly once`() {
        var turns = 0
        var asks = 0
        repeat(ReviewPrompt.TURNS_BEFORE_ASK * 2) {
            turns = ReviewPrompt.recordTurn(turns)
            if (ReviewPrompt.shouldAsk(turns, asked = asks > 0)) asks++
        }
        assertEquals(1, asks, "the prompt must fire exactly once, ever")
    }

    @Test
    fun `the review URL points at this plugin's Marketplace reviews tab over https`() {
        assertTrue(ReviewPrompt.REVIEW_URL.startsWith("https://plugins.jetbrains.com/plugin/"))
        assertTrue(ReviewPrompt.REVIEW_URL.endsWith("/reviews"))
    }
}
