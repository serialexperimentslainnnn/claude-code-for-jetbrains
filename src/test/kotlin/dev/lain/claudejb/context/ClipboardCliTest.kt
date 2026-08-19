package dev.lain.claudejb.context

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ClipboardCliTest {

    @Test
    fun `preferredTextType prefers a utf-8 plain-text mime`() {
        assertEquals(
            "text/plain;charset=utf-8",
            ClipboardCli.preferredTextType(listOf("text/html", "text/plain;charset=utf-8", "text/plain")),
        )
    }

    @Test
    fun `preferredTextType accepts X11 atom targets from xclip`() {
        assertEquals(
            "UTF8_STRING",
            ClipboardCli.preferredTextType(listOf("TARGETS", "STRING", "UTF8_STRING", "TEXT")),
        )
        assertEquals("STRING", ClipboardCli.preferredTextType(listOf("TARGETS", "STRING", "TEXT")))
    }

    @Test
    fun `preferredTextType falls back to plain then any other text type`() {
        assertEquals("text/plain", ClipboardCli.preferredTextType(listOf("text/html", "text/plain")))
        assertEquals("text/markdown", ClipboardCli.preferredTextType(listOf("text/markdown")))
    }

    @Test
    fun `preferredTextType returns null for an image-only clipboard (the Wayland leak guard)`() {
        val kdeImageTypes = listOf(
            "image/png",
            "application/x-qt-image",
            "x-kde-force-image-copy",
            "application/x-kde-suggestedfilename",
            "image/avif",
            "image/bmp",
        )
        assertNull(ClipboardCli.preferredTextType(kdeImageTypes))
    }

    @Test
    fun `preferredTextType excludes uri-list and html (copied files and markup are not a plain paste)`() {
        assertNull(ClipboardCli.preferredTextType(listOf("text/uri-list")))
        assertNull(ClipboardCli.preferredTextType(listOf("text/html")))
    }

    @Test
    fun `preferredTextType returns null for an empty listing`() {
        assertNull(ClipboardCli.preferredTextType(emptyList()))
    }
}
