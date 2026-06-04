package dev.lain.claudejb.context

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pure-JVM coverage of [Attachment.toPromptText] and the [Attachment.Selection] display name.
 * These are the prompt-text fallbacks the context actions emit until the composer grows a real
 * attachment sink, so the exact `@path` / fenced `path:line` shapes are load-bearing.
 */
class AttachmentTest {

    @Test
    fun `FileRef prompt text is an at-mention`() {
        val ref = Attachment.FileRef(path = "/proj/src/Main.kt", displayName = "Main.kt")
        assertEquals("@/proj/src/Main.kt", ref.toPromptText())
    }

    @Test
    fun `Selection prompt text fences with path and line and lang`() {
        val sel = Attachment.Selection(
            path = "/proj/src/Main.kt",
            startLine = 42,
            text = "fun main() {}",
            lang = "kotlin",
        )
        assertEquals(
            "```kotlin /proj/src/Main.kt:42\nfun main() {}\n```",
            sel.toPromptText(),
        )
    }

    @Test
    fun `Selection prompt text preserves an existing trailing newline`() {
        val sel = Attachment.Selection(path = "a.py", startLine = 1, text = "x = 1\n", lang = "python")
        assertEquals("```python a.py:1\nx = 1\n```", sel.toPromptText())
    }

    @Test
    fun `Selection prompt text tolerates a null lang`() {
        val sel = Attachment.Selection(path = "a.txt", startLine = 3, text = "hi", lang = null)
        assertEquals("``` a.txt:3\nhi\n```", sel.toPromptText())
    }

    @Test
    fun `Selection display name is filename and line`() {
        val sel = Attachment.Selection(path = "/proj/src/Main.kt", startLine = 7, text = "x", lang = "kotlin")
        assertEquals("Main.kt:7", sel.displayName)
    }

    @Test
    fun `Image prompt text is a placeholder marker`() {
        val img = Attachment.Image(displayName = "shot.png", mediaType = "image/png", base64 = "AAAA")
        assertEquals("[image: shot.png]", img.toPromptText())
    }
}
